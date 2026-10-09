package org.example.tracemind.pojo.dto;

import lombok.Data;
import org.example.tracemind.common.enums.LogLevel;
import org.springframework.util.MultiValueMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 日志检索条件，对应 {@code GET /api/projects/{id}/logs}。
 * <p>查询直接下发到 ES（按 timestamp desc），不经过 MySQL。</p>
 *
 * <h3>⚠ 前端传的是 {@code level[]} 而不是 {@code level}</h3>
 * 前端 {@code types/log.ts} 里 {@code level?: LogLevel[]} 是<b>数组</b>，
 * axios 1.x 对数组参数的默认序列化是<b>带方括号</b>的：
 * <pre>
 *   GET /api/projects/p-001/logs?level%5B%5D=ERROR&level%5B%5D=WARN
 * </pre>
 * Spring 的 {@code @RequestParam List<String> level} <b>绑不上</b>这个参数名（它找的是 {@code level}），
 * 结果就是"前端明明选了 ERROR，后端却按不过滤返回全量"——一个不报错、只出错数据的坑。
 *
 * <p>所以这里提供 {@link #fromParams(MultiValueMap)}，同时吃 {@code level} 和 {@code level[]} 两种写法。
 * Controller 用 {@code @RequestParam MultiValueMap<String, String> params} 接住整个查询串再交给它，
 * 参数名的兼容逻辑就集中在这一个地方。</p>
 */
@Data
public class LogQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全文关键字，命中 rawText / template / stackTrace */
    private String keyword;

    /** 可选多选：ERROR / WARN / INFO / DEBUG / TRACE，为空表示不过滤 */
    private List<String> level;

    /** 起始时间，ISO-8601 字符串，如 2026-09-10T00:00:00Z */
    private String from;

    /** 结束时间，ISO-8601 字符串 */
    private String to;

    /** Drain3 聚类 id，前端 LogQuery 类型里有这个字段 */
    private String clusterId;

    private Integer page = 1;

    private Integer size = 20;

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
     * 从原始查询串构条件，兼容 axios 的 {@code level[]} 写法。
     * <p>未知的 level 值直接丢掉，不报错——多选过滤里少一个值比整个请求 400 体验好得多。</p>
     */
    public static LogQueryDTO fromParams(MultiValueMap<String, String> params) {
        LogQueryDTO query = new LogQueryDTO();
        query.setKeyword(first(params, "keyword"));
        query.setClusterId(first(params, "clusterId"));
        query.setFrom(first(params, "from"));
        query.setTo(first(params, "to"));
        query.setLevel(mergeLevels(params));
        query.setPage(toInteger(first(params, "page")));
        query.setSize(toInteger(first(params, "size")));
        return query;
    }

    private static String first(MultiValueMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static List<String> mergeLevels(MultiValueMap<String, String> params) {
        Set<String> levels = new LinkedHashSet<>();
        collectLevels(params, "level", levels);
        collectLevels(params, "level[]", levels);
        return levels.isEmpty() ? null : new ArrayList<>(levels);
    }

    private static void collectLevels(MultiValueMap<String, String> params, String name, Set<String> target) {
        List<String> values = params.get(name);
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            // 兼容 "ERROR,WARN" 这种逗号分隔的写法
            for (String part : value.split(",")) {
                LogLevel level = LogLevel.of(part.trim());
                if (level != null) {
                    target.add(level.code());
                }
            }
        }
    }

    private static Integer toInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
