package org.example.tracemind.common.enums;

/**
 * 诊断会话状态，契约见说明书 3.2 节 DiagnosisSession.status。
 *
 * <p>{@link #DEGRADED} 是这个系统的特色状态：Python 服务挂了不代表诊断失败，
 * Java 本地跑一遍极简关键词规则、把阶段 2 已存的解法捞出来返回，用户至少能拿到点东西。
 * 前端会把它渲染成"降级结果"而不是错误页。</p>
 */
public enum SessionStatus {

    /** 诊断进行中 */
    RUNNING,

    /** 正常跑完，有完整结论 */
    SUCCEEDED,

    /** 出错终止 */
    FAILED,

    /** Python 不可用，降级返回规则结论 */
    DEGRADED;

    public String code() {
        return name();
    }

    /**
     * 是否终态。
     * <p>WS 连接和幂等判断都依赖这个：终态要主动关连接；非终态的同 logId 会话才允许被复用。</p>
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    public static SessionStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (SessionStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
