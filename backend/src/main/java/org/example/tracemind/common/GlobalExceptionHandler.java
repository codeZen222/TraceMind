package org.example.tracemind.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。所有异常统一转成 {@link ApiResponse}，Controller 里一个 try/catch 都不用写。
 *
 * <h3>为什么 HTTP 状态码恒为 200，错误只写在 body 的 code 里</h3>
 * 《TraceMind-接口开发手册》0.1 节把错误信息统一约定在 body 的 {@code code} / {@code message} 里，
 * 前端 {@code api/request.ts} 也正是在 {@code body.code !== 0} 时抛 {@code body.message}。
 * 也就是说：<b>错误的语义已经由 body 承载了</b>，再让 HTTP 状态码表达一遍就是两套真相。
 * 保持 200 的好处是所有错误只走一条分支——curl、Postman、前端拦截器行为完全一致，
 * 排障时不用先判断"这个 404 是路由没匹配上还是业务说没查到"。
 *
 * <p>补充一句实话：前端的错误拦截器是
 * {@code err => new Error(err?.response?.data?.message ?? '网络请求失败')}，
 * 它<b>也会</b>读 4xx/5xx 响应体里的 message。所以回真实状态码同样能跑通，这不是硬约束。
 * 要切换的话，把下面这些方法的返回值改成 {@code ResponseEntity}、用 code 推导 status 即可，
 * 改动只在这一个类里。</p>
 *
 * <p>代价是 5xx 不再体现在 HTTP 状态上，所以<b>告警要基于日志</b>：
 * 兜底分支固定打 {@code log.error} 带完整堆栈，监控按 ERROR 级别日志报警，而不是按状态码。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：Service 主动抛的，属于"正常业务分支"，只打 warn 不打 error，免得污染告警 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 校验 @RequestBody 失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return ApiResponse.fail(ResultCode.BAD_REQUEST, collectFieldErrors(e));
    }

    /** @Valid 校验 @ModelAttribute（表单/查询参数）失败 */
    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException e) {
        return ApiResponse.fail(ResultCode.BAD_REQUEST, collectFieldErrors(e));
    }

    /** @Validated 校验方法参数（如 @PathVariable @Min(1)）失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", message);
        return ApiResponse.fail(ResultCode.BAD_REQUEST, message);
    }

    /** 请求体不是合法 JSON、或类型对不上 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.fail(ResultCode.BAD_REQUEST, "请求体格式错误，无法解析为 JSON");
    }

    /** 路径变量 / 查询参数类型不匹配，如 page=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数 " + e.getName() + " 类型不正确";
        log.warn("{}: value={}", message, e.getValue());
        return ApiResponse.fail(ResultCode.BAD_REQUEST, message);
    }

    /** 必填查询参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(ResultCode.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 方法不支持，如用 GET 打 POST 接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.fail(ResultCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /** 路径压根不存在（Spring 6.1+ 静态资源没命中会抛这个，别让它掉进兜底变成 500） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.fail(ResultCode.NOT_FOUND, "接口不存在: " + e.getResourcePath());
    }

    /**
     * 兜底。走到这里说明是我们没预料到的 bug，必须打完整堆栈——这是唯一的告警入口。
     * 对外只回一句笼统的话，不把堆栈/类名/SQL 泄露给前端（信息泄露也是攻击面）。
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("未捕获异常", e);
        return ApiResponse.fail(ResultCode.INTERNAL_ERROR, "服务器内部错误，请查看后端日志");
    }

    private String collectFieldErrors(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        if (message.isEmpty()) {
            message = ResultCode.BAD_REQUEST.getMessage();
        }
        log.warn("参数校验失败: {}", message);
        return message;
    }
}
