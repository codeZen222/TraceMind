package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 监控状态响应体，契约见说明书 3.2 节 **MonitorStatus**。
 *
 * <p>对应 {@code GET /api/projects/{id}/monitor}。注意契约要求：<b>该项目没有监控任务时不能 404</b>，
 * 要返回一个 {@code running=false}、其余字段为默认值的对象。</p>
 */
@Data
public class MonitorStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 t-001；无任务时为 null */
    private String taskId;

    private String projectId;

    /** 是否正在采集 */
    private Boolean running;

    private String filePath;

    /** 已读字节偏移（真身在 Redis collector:pos:{taskId}，Redis 不可用时回落 MySQL 快照） */
    private Long offset;

    private Long fileSize;

    private Instant lastReadAt;

    /** 累计已读行数。ES 文档 ID 里的行号由它推导，重启后续读必须接着上次的值 */
    private Long linesRead;

    private Integer errorsDetected;

    private Integer ruleMatched;

    private Integer agentTriggered;

    /**
     * Python AI 服务不可用（熔断打开）。
     *
     * <p>影响面：<b>只有模板提取与聚类</b>——日志照常采集入库，{@code template / clusterId} 留空，
     * 阶段 1、2 的规则诊断也不受影响（它们不依赖 Drain3 的新模板）。
     * 见 {@code PythonClient#isDegraded()}。</p>
     */
    private Boolean aiDegraded;

    /**
     * 位点存储（Redis）不可用，当前 {@code offset / linesRead} 读的是 MySQL 兜底快照。
     *
     * <p>和 {@link #aiDegraded} 是两种完全不同的故障：这里不影响任何日志内容，
     * 只是进度可能滞后最多 30 秒（快照周期），恢复后可能重读若干行。
     * 拆成两个字段是因为混在一个布尔里时，前端和用户都分不清该找谁——
     * 一个要去看 AI 服务，一个要去看 Redis。</p>
     */
    private Boolean positionDegraded;

    /** 无任务时用的空壳对象，避免前端判空 */
    public static MonitorStatusVO empty(String projectId) {
        MonitorStatusVO vo = new MonitorStatusVO();
        vo.setProjectId(projectId);
        vo.setRunning(false);
        vo.setOffset(0L);
        vo.setFileSize(0L);
        vo.setLinesRead(0L);
        vo.setErrorsDetected(0);
        vo.setRuleMatched(0);
        vo.setAgentTriggered(0);
        vo.setAiDegraded(false);
        vo.setPositionDegraded(false);
        return vo;
    }
}
