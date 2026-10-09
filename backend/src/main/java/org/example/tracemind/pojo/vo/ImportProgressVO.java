package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 历史导入进度，契约见说明书 3.3 节 **{@code GET /api/logs/import/{taskId}/progress}**。
 *
 * <p>响应 {@code {total, processed, status}}，前端按 {@code processed / total} 画进度条。
 * {@code total} 是预估值（用 file.size() / 平均行长 估），会边读边修正。</p>
 */
@Data
public class ImportProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 预估总行数 */
    private Integer total;

    /** 已处理行数 */
    private Integer processed;

    /** RUNNING | DONE | FAILED */
    private String status;
}
