package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 诊断事件表 agent_event。
 * <p>硬性约束：事件 MUST 先落这张表，再推 WebSocket；断线重连按 seq 升序回放。</p>
 * <p>seq 由 Redis 计数器 {@code INCR diag:seq:{sessionId}} 生成，会话内严格自增，
 * 配合 {@code UNIQUE KEY uk_session_seq (session_id, seq)} 兜底防重。</p>
 *
 * @see org.example.tracemind.pojo.vo.DiagnosisEventVO
 * @see org.example.tracemind.pojo.vo.WsMessageVO
 */
@Data
public class AgentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    private Long id;

    private String sessionId;

    /** 会话内自增序号 */
    private Integer seq;

    /** stage | rule_hit | tool_call | tool_result | token | thinking | result | error */
    private String eventType;

    /** 事件体的原始 JSON 字符串，对应 DiagnosisEventVO 序列化结果 */
    private String payload;

    private LocalDateTime createdAt;
}
