package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目表 project。
 * <p>与 MySQL 表一一对应的持久化对象，只允许在 mapper / service 层流转，不直接返回前端（对外用 ProjectVO）。</p>
 *
 * @see org.example.tracemind.pojo.vo.ProjectVO
 */
@Data
public class Project implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 p-001 */
    private String id;

    /** 项目名 */
    private String name;

    private String description;

    /** DEVELOPING | DEPLOYED */
    private String status;

    /** 技术栈描述，如 Java / Spring Boot */
    private String language;

    /** UPLOAD | PATH */
    private String source;

    /** source=PATH 时为日志文件绝对路径；source=UPLOAD 时为上传文件夹名 */
    private String logPath;

    /** 仅 source=UPLOAD 时有值：文件夹内日志文件数 */
    private Integer fileCount;

    /** 健康检查地址，Python 的 check_project_health 工具会用 */
    private String healthUrl;

    /** 源码根目录，Python 的 read_source_code 工具会用 */
    private String sourceRoot;

    /** 所属用户 id（多租户：单 owner 模型，所有项目都必须有 owner） */
    private String ownerId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
