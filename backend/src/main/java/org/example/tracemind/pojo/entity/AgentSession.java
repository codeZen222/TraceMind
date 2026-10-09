package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诊断会话表 agent_session。
 * <p>一次诊断 = 一条会话记录。结论字段（rootCause / suggestion / confidence）从 result 事件里抽出来落平，
 * 完整 result（evidence、relatedLogIds 等）存在 {@link #resultJson} 里。</p>
 *
 * @see org.example.tracemind.pojo.vo.DiagnosisSessionVO
 */
@Data
public class AgentSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 sess-a1b2c3 */
    private String sessionId;

    private String projectId;

    /** RUNNING | SUCCEEDED | FAILED | DEGRADED */
    private String status;

    /** 触发本次诊断的日志 id（幂等键：同 logId 存在 RUNNING 会话则直接复用） */
    private String triggerLogId;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 端到端耗时（毫秒） */
    private Long totalCostMs;

    /** 是否走了阶段 3 LLM */
    private Boolean llmUsed;

    /** 阶段 2 命中的 Analyzer 名，未命中为 null */
    private String matchedAnalyzer;

    private String rootCause;

    private String suggestion;

    /** 置信度 0.00 ~ 1.00 */
    private BigDecimal confidence;

    private Integer toolCallCount;

    /** 完整 result 的原始 JSON 字符串，形如 {"evidence":[...],"relatedLogIds":[...]} */
    private String resultJson;
}
