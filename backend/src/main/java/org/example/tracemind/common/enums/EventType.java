package org.example.tracemind.common.enums;

/**
 * 诊断事件类型，契约见说明书 3.2 节 DiagnosisEvent 的 8 种 {@code type}。
 *
 * <p><b>为什么不能直接序列化枚举：</b>Java 常量习惯全大写（{@code TOOL_CALL}），
 * 但线上契约要的是小写下划线（{@code tool_call}）。直接 {@code name()} 出去前端就全匹配不上了。
 * 所以这里显式带一个 {@code code} 字段，任何往外发的地方都只用 {@link #getCode()}，
 * 同时这张表也是"8 种 type 一个不多一个不少"的唯一出处——数据库 {@code agent_event.event_type}
 * 列注释和它必须保持一致。</p>
 */
public enum EventType {

    /** 阶段进度：{stage, name, status, costMs} */
    STAGE("stage"),

    /** 阶段 2 规则命中，命中即结束：{analyzer, solution, costMs} */
    RULE_HIT("rule_hit"),

    /** Agent 调工具：{tool, args} */
    TOOL_CALL("tool_call"),

    /** 工具返回：{tool, summary, truncated, costMs} */
    TOOL_RESULT("tool_result"),

    /** 流式回答片段：{content} */
    TOKEN("token"),

    /** 模型思考过程：{content} */
    THINKING("thinking"),

    /** 最终结论：{rootCause, evidence, suggestion, confidence} */
    RESULT("result"),

    /** 出错：{stage, message} */
    ERROR("error");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /** 线上取值，唯一允许出现在 JSON 里的形式 */
    public String getCode() {
        return code;
    }

    public static EventType of(String code) {
        if (code == null) {
            return null;
        }
        for (EventType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    /** 是否是会话终结事件，收到后要落终态并关闭 WS */
    public boolean isTerminal() {
        return this == RESULT || this == ERROR;
    }
}
