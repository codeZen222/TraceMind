package org.example.tracemind.common;

import lombok.Getter;

/**
 * 业务返回码。取值以《TraceMind-接口开发手册》0.1 节为准，与前端对齐全靠它。
 *
 * <p>约定：{@code 0} = 成功，非 0 = 业务错误。</p>
 *
 * <p>前端 {@code src/api/request.ts} 只做一件事：{@code if (body.code !== 0) throw new Error(body.message)}。
 * <b>它不识别具体码值</b>，所以这里加码值是为了让日志和排障有语义，不是为了让前端分支。</p>
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "ok"),

    /** 参数校验失败，message 说明哪个字段 */
    BAD_REQUEST(40000, "参数错误"),

    UNAUTHORIZED(40100, "未认证"),

    FORBIDDEN(40300, "无权访问该资源"),

    /** 项目 / 会话 / 日志 / 报告 id 查不到 */
    NOT_FOUND(40400, "资源不存在"),

    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),

    CONFLICT(40900, "资源状态冲突"),

    /** 未预期异常，全局处理器兜底 */
    INTERNAL_ERROR(50000, "系统错误"),

    /** Python 探活失败 / 诊断降级 */
    SERVICE_UNAVAILABLE(50300, "AI 服务不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
