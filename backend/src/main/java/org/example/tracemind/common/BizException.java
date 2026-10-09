package org.example.tracemind.common;

import lombok.Getter;

/**
 * 业务异常。Service 层遇到"可预期的错误"就抛它，由 {@link GlobalExceptionHandler} 统一转成 {@link ApiResponse}。
 *
 * <p>为什么不用 {@code RuntimeException} 裸抛：裸抛丢掉了 code，全局处理器只能一律给 500，
 * 结果"项目不存在"这种正常业务分支会变成告警里的 5xx 噪音，把真正的故障淹掉。</p>
 *
 * <p>惯例用法：{@code throw BizException.notFound("项目不存在: " + id);}</p>
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        this(ResultCode.BAD_REQUEST, message);
    }

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    // ---------- 高频场景快捷构造 ----------

    public static BizException badRequest(String message) {
        return new BizException(ResultCode.BAD_REQUEST, message);
    }

    /** 资源不存在。跨项目越权访问也走这个——不暴露"存在但你没权限"，避免枚举探测 */
    public static BizException notFound(String message) {
        return new BizException(ResultCode.NOT_FOUND, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ResultCode.FORBIDDEN, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ResultCode.CONFLICT, message);
    }

    /** 依赖的下游（Python / ES / LLM）不可用 */
    public static BizException serviceUnavailable(String message) {
        return new BizException(ResultCode.SERVICE_UNAVAILABLE, message);
    }
}
