package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * Elasticsearch 索引 log_entry 的文档模型。
 * <p>注意：它<b>不是</b> MySQL 表实体，日志量大、检索重，全部走 ES，绝不落 MySQL。
 * 索引映射见 {@code resources/db/es-log_entry-index.json}。</p>
 *
 * <p>时间字段用 String（ISO-8601，如 {@code 2026-09-10T18:29:03.512Z}）而不是 LocalDateTime：
 * ES 映射写的是 {@code strict_date_optional_time||epoch_millis}，字符串形式正好能被它直接解析，
 * 既不用给 elasticsearch-java 的 JacksonJsonpMapper 配日期格式，也不会踩 JDK8 日期被序列化成 epoch 数字的坑。</p>
 *
 * @see org.example.tracemind.pojo.vo.LogEntryVO
 */
@Data
public class LogEntryDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档主键，形如 log-0001 */
    private String id;

    private String projectId;

    /** 日志时间，ISO-8601 字符串 */
    private String timestamp;

    /** ERROR | WARN | INFO | DEBUG */
    private String level;

    /** 原始日志行 */
    private String rawText;

    /** Drain3 提取出的模板，如 {@code NullPointerException at *} */
    private String template;

    /** Drain3 聚类 id，如 cluster_12 */
    private String clusterId;

    /** 来源文件绝对路径 */
    private String filePath;

    /** 行号 */
    private Integer lineNumber;

    /** 完整堆栈，可能多行 */
    private String stackTrace;
}
