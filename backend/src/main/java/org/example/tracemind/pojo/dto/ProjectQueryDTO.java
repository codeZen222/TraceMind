package org.example.tracemind.pojo.dto;

import lombok.Data;
import org.example.tracemind.common.enums.ProjectStatus;

import java.io.Serializable;

/**
 * 项目列表查询条件，对应 {@code GET /api/projects?page&size&status&keyword}。
 * <p>查询参数不做 @Valid 校验——page/size 非法时由下面的兜底方法修正，不报 40000。
 * 列表页的筛选是"尽力而为"的交互，因为一个手写的 query 参数就打断整个页面不值得。</p>
 */
@Data
public class ProjectQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数 */
    private Integer size = 20;

    /** 可选：DEVELOPING | DEPLOYED | ALL */
    private String status;

    /** 可选：按项目名模糊搜索 */
    private String keyword;

    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    public int sizeOrDefault() {
        return size == null || size < 1 ? 20 : Math.min(size, 200);
    }

    public int offset() {
        return (pageOrDefault() - 1) * sizeOrDefault();
    }

    /**
     * 取有效的状态过滤值，无效时返回 null（= 不过滤）。
     *
     * <p><b>关键点：前端的"全部"选项传的是字符串 {@code ALL}，不是不传。</b>
     * 前端 {@code api/project.ts} 里 {@code status?: ProjectStatus | 'ALL'}，
     * 项目列表页的下拉框"全部"绑定的就是 {@code ALL}。
     * 如果直接把它拼进 SQL 的 {@code WHERE status = ?}，结果恒为空集——
     * 用户看到的是"项目全没了"，但接口一切正常、没有任何报错，属于最难查的那类 bug。</p>
     */
    public String statusOrNull() {
        ProjectStatus parsed = ProjectStatus.of(status);
        return parsed == null ? null : parsed.name();
    }
}
