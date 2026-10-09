package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 日志统计响应体，契约见说明书 3.2 节 **LogStats**。
 *
 * <p>三个字段分别对应 {@code EsLogRepository} 里的三个 ES 聚合：</p>
 * <ul>
 *   <li>{@code levelDistribution} → terms 聚合 on level</li>
 *   <li>{@code frequencyTrend} → date_histogram 聚合 on timestamp</li>
 *   <li>{@code topTemplates} → terms 聚合 on template（按 count 降序取 Top N）</li>
 * </ul>
 */
@Data
public class LogStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<LevelDistributionItem> levelDistribution;

    private List<FrequencyTrendItem> frequencyTrend;

    private List<TopTemplateItem> topTemplates;

    /** 级别分布：{ level, count } */
    @Data
    public static class LevelDistributionItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** ERROR | WARN | INFO | DEBUG */
        private String level;

        private Long count;
    }

    /** 频率趋势：{ time, count }，time 是 "HH:mm" 形式的桶标签 */
    @Data
    public static class FrequencyTrendItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 如 18:00 */
        private String time;

        private Long count;
    }

    /** 高频模板 Top N：{ template, clusterId, count, level } */
    @Data
    public static class TopTemplateItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private String template;

        private String clusterId;

        private Long count;

        /** 该模板的主要日志级别 */
        private String level;
    }
}
