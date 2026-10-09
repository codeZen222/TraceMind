package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 复盘报告表 report。
 * <p>markdown 是最终产物，session_ids 存 JSON 数组字符串（如 {@code ["sess-a1b2c3"]}）。</p>
 *
 * @see org.example.tracemind.pojo.vo.ReportVO
 */
@Data
public class Report implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 r-001 */
    private String id;

    private String projectId;

    private String title;

    /** JSON 数组字符串，对应前端 {@code sessionIds: string[]} */
    private String sessionIds;

    /** 完整 Markdown 正文 */
    private String markdown;

    /** 生成时统计的日志条数 */
    private Integer logCount;

    /** 纳入报告的诊断会话数 */
    private Integer diagnosisCount;

    private LocalDateTime createdAt;
}
