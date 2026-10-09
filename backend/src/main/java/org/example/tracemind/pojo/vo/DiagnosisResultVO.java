package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 诊断结论，契约见说明书 3.2 节 **DiagnosisSession.result**。
 *
 * <p>对应 MySQL 的 {@code agent_session} 表：{@code rootCause / suggestion / confidence / toolCallCount}
 * 是平铺列，{@code evidence / relatedLogIds} 等其余字段存在 {@code result_json} 列里。
 * 落库时拆开存，读出来再组装成这个对象。</p>
 */
@Data
public class DiagnosisResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 根因结论 */
    private String rootCause;

    /** 证据链，如 ["log-0001: NPE at OrderService.createOrder:87", "同类错误 24h 内出现 21 次"] */
    private List<String> evidence;

    /** 修复建议 */
    private String suggestion;

    /** 置信度 0 ~ 1 */
    private Double confidence;

    /** 阶段 3 Agent 实际调用工具的次数 */
    private Integer toolCallCount;

    /** 关联的日志 id 列表 */
    private List<String> relatedLogIds;
}
