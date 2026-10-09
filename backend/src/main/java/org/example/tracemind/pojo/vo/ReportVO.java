package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 复盘报告响应体，契约见说明书 3.2 节 **Report**。
 *
 * <p>{@code sessionIds} 在库里是 JSON 列（字符串），到这一层必须转成真正的数组，否则前端拿到的是字符串。</p>
 */
@Data
public class ReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 r-001 */
    private String id;

    private String projectId;

    private String title;

    private List<String> sessionIds;

    /** 完整 Markdown 正文；列表接口可置 null 省流量，详情/下载接口必返 */
    private String markdown;

    private Instant createdAt;

    /** 生成时统计的日志条数 */
    private Integer logCount;

    /** 纳入报告的诊断会话数 */
    private Integer diagnosisCount;
}
