package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 新错误条目 —— 新错误雷达面板的一行。
 *
 * <p>组装来源两处：{@code clusterId / firstSeenAt} 来自基线表 error_baseline，
 * {@code template / count} 来自 ES 聚合（基线表只存指纹，不存模板和条数）。</p>
 */
@Data
public class NewErrorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 错误指纹（Drain3 聚类 id） */
    private String clusterId;

    /** 该错误的 Drain3 模板（示例日志） */
    private String template;

    /** 首次出现时间，ISO-8601 字符串 */
    private String firstSeenAt;

    /** 该项目该错误模板的条数 */
    private long count;
}
