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

    /** 已读字节偏移（真身在 Redis offset:{taskId}） */
    private Long offset;

    private Long fileSize;

    private Instant lastReadAt;

    private Long linesRead;

    private Integer errorsDetected;

    private Integer ruleMatched;

    private Integer agentTriggered;

    /** Python 推理服务是否不可用（降级模式） */
    private Boolean degraded;

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
        vo.setDegraded(false);
        return vo;
    }
}
