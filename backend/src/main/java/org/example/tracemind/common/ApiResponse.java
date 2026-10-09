package org.example.tracemind.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应包装，契约见说明书 3.1 节：{@code { "code": 0, "message": "ok", "data": { } }}。
 *
 * <p>两个刻意的设计：</p>
 * <ol>
 *   <li>{@code data} 为 null 时<b>照样输出</b>（不套 {@code @JsonInclude(NON_NULL)}）。
 *       契约里 {@code "fileCount": null} 是明着写的，前端按字段存在做事，少一个 key 可能就崩。</li>
 *   <li>提供 {@code ok(...)} / {@code fail(...)} 静态工厂而不是裸 new，Controller 里只有两种写法，
 *       不会有人手滑把 code 写反。</li>
 * </ol>
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String message;

    private T data;

    public ApiResponse(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ---------- 成功 ----------

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    // ---------- 失败 ----------

    public static <T> ApiResponse<T> fail(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ResultCode resultCode, String message) {
        return new ApiResponse<>(resultCode.getCode(), message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    // 注意：不要在这个类里加 isXxx() / getXxx() 形式的方法！
    // 它们会被 Jackson 当成属性一起序列化出去，响应体就会变成
    // {"code":0,"message":"ok","data":{},"success":true} —— 契约里只允许有三个字段。
    // 判断成功请直接比较 code，或放在别的地方做。
}
