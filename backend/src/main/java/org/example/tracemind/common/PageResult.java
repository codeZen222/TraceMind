package org.example.tracemind.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页响应，契约见说明书 3.1 节：
 * {@code data = { "list": [...], "total": 100, "page": 1, "size": 20 }}。
 *
 * <p>注意 {@code total} 是<b>总数而不是本页条数</b>，前端算总页数要靠它；
 * {@code page} 从 1 开始，和前端分页组件一致。</p>
 *
 * @param <T> 列表元素类型，通常是某个 VO
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> list;

    /** 满足条件的总记录数 */
    private Long total;

    /** 当前页码，从 1 开始 */
    private Integer page;

    /** 每页条数 */
    private Integer size;

    public PageResult(List<T> list, Long total, Integer page, Integer size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }

    /** 空页。查询没命中时要返回它，而不是 null——前端分页组件需要 total 才能渲染 */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(Collections.emptyList(), 0L, page, size);
    }
}
