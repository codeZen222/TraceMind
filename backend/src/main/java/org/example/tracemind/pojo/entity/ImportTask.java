package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 历史日志导入任务表 import_task。
 * <p>进度靠 {@code processed / total} 算，前端轮询 {@code /api/logs/import/{taskId}/progress}。</p>
 */
@Data
public class ImportTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 imp-xxxx */
    private String taskId;

    private String projectId;

    /** 预估总行数（用 file.size()/平均行长 估算，边读边修正） */
    private Integer total;

    /** 已处理行数 */
    private Integer processed;

    /** RUNNING | DONE | FAILED */
    private String status;

    private LocalDateTime createdAt;
}
