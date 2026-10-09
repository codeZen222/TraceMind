package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 监控任务表 log_monitor_task。
 * <p>一个项目对应一个采集任务，offset 以 Redis 的 {@code offset:{taskId}} 为准，这张表只做持久化快照与页面展示。</p>
 *
 * @see org.example.tracemind.pojo.vo.MonitorStatusVO
 */
@Data
public class LogMonitorTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 t-001 */
    private String taskId;

    private String projectId;

    /** 是否正在采集（TINYINT(1)） */
    private Boolean running;

    /** 被监控的日志文件绝对路径 */
    private String filePath;

    /** 已读字节偏移 */
    private Long offset;

    /** 当前文件大小，用于算进度 */
    private Long fileSize;

    private LocalDateTime lastReadAt;

    /** 累计读取行数 */
    private Long linesRead;

    /** 累计识别出的错误行数 */
    private Integer errorsDetected;

    /** 累计命中阶段 2 规则的数量 */
    private Integer ruleMatched;

    /** 累计触发阶段 3 Agent 的数量 */
    private Integer agentTriggered;

    /** Python 推理服务是否不可用（TINYINT(1)） */
    private Boolean degraded=false;
}
