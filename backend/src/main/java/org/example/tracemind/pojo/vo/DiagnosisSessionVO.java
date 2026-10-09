package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 诊断会话响应体，契约见说明书 3.2 节 **DiagnosisSession**。
 *
 * <p>对应 MySQL 的 {@code agent_session}，另有两处是组装出来的：</p>
 * <ul>
 *   <li>{@code projectName}——冗余字段，前端列表免二次查询，从 project 表 join 出来</li>
 *   <li>{@code events}——从 {@code agent_event} 按 seq 升序读出，是 WS 历史回放的同一份数据</li>
 * </ul>
 */
@Data
public class DiagnosisSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 sess-a1b2c3 */
    private String sessionId;

    private String projectId;

    /** 冗余字段，省掉前端二次查询 */
    private String projectName;

    /** RUNNING | SUCCEEDED | FAILED | DEGRADED */
    private String status;

    /** 触发本次诊断的那条日志（详情接口带上，列表接口可为 null） */
    private LogEntryVO triggerLog;

    private Instant startedAt;

    private Instant finishedAt;

    private Long totalCostMs;

    /** 是否走了阶段 3 LLM */
    private Boolean llmUsed;

    /** 阶段 2 命中的 Analyzer 名，未命中为 null */
    private String matchedAnalyzer;

    /** 结论，未出结论时为 null */
    private DiagnosisResultVO result;

    /** 事件历史，按 seq 升序 */
    private List<WsMessageVO> events;
}
