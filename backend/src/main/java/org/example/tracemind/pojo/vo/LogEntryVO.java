package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 日志响应体，契约见说明书 3.2 节 **LogEntry**。
 *
 * <p>数据来自 Elasticsearch（索引 log_entry），不是 MySQL。字段名与 ES 文档模型 {@code LogEntryDoc} 完全一致，
 * 因为 ES 里存的就是驼峰字段，中间不需要再做一次命名转换。</p>
 *
 * <p>{@code timestamp} 保持 ISO-8601 字符串（如 {@code 2026-09-10T18:29:03.512Z}），理由见 {@code LogEntryDoc}。</p>
 */
@Data
public class LogEntryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 log-0001 */
    private String id;

    private String projectId;

    /** ISO-8601 字符串 */
    private String timestamp;

    /** ERROR | WARN | INFO | DEBUG */
    private String level;

    /** 原始日志行 */
    private String rawText;

    /** Drain3 模板，如 NullPointerException at * */
    private String template;

    /** Drain3 聚类 id，如 cluster_12 */
    private String clusterId;

    private String filePath;

    private Integer lineNumber;

    /** 列表接口可不返回，详情接口 {@code /logs/{logId}} 必返完整堆栈 */
    private String stackTrace;
}
