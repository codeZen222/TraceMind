package org.example.tracemind.common.enums;

/**
 * 追问消息角色，契约见说明书 3.2 节 ChatMessage.role 与 {@code chat_message.role} 列。
 *
 * <p>线上取值是小写（{@code user} / {@code assistant}），而 Java 常量习惯全大写，
 * 直接 {@code name()} 出去会变成 {@code USER}，前端按 {@code role === 'user'} 判断就失效了。
 * 所以这里显式带 {@code code}。</p>
 *
 * <p>拼给 Python 的多轮 messages 时也要用 {@link #getCode()}，
 * 因为 OpenAI 兼容协议只认小写的 {@code user} / {@code assistant}。</p>
 */
public enum ChatRole {

    USER("user"),

    ASSISTANT("assistant");

    private final String code;

    ChatRole(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ChatRole of(String code) {
        if (code == null) {
            return null;
        }
        for (ChatRole role : values()) {
            if (role.code.equalsIgnoreCase(code)) {
                return role;
            }
        }
        return null;
    }
}
