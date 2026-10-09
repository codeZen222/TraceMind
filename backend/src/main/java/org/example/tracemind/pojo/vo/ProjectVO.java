package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 项目响应体，契约见说明书 3.2 节 **Project**。
 *
 * <p>比实体 {@code Project} 多两个<b>派生字段</b>——这就是 entity / vo 必须分家的根本原因，表里根本没这两列：</p>
 * <ul>
 *   <li>{@code errorCount24h}：ES 对 log_entry 做 range + count 得到</li>
 *   <li>{@code diagnosisCount}：MySQL 对 agent_session 做 count 得到</li>
 * </ul>
 *
 * <p>时间字段用 {@link Instant} 而不是 LocalDateTime：Jackson 序列化成 {@code 2026-09-01T10:00:00Z}，
 * 与前端 mock 的字符串逐字一致；LocalDateTime 出来的串没有 Z 后缀，前端 {@code new Date()} 会按本地时区解析。</p>
 */
@Data
public class ProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 p-001 */
    private String id;

    private String name;

    private String description;

    /** DEVELOPING | DEPLOYED */
    private String status;

    private String language;

    /** UPLOAD | PATH */
    private String source;

    private String logPath;

    /** 仅 UPLOAD 模式有值 */
    private Integer fileCount;

    private String healthUrl;

    private String sourceRoot;

    private Instant createdAt;

    private Instant updatedAt;

    /** 派生：24 小时内的错误日志条数（ES count） */
    private Integer errorCount24h;

    /** 派生：已完成的诊断会话数（MySQL count） */
    private Integer diagnosisCount;
}
