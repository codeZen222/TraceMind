package org.example.tracemind.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.LogQueryDTO;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Repository
@RequiredArgsConstructor
public class EsLogRepository {

    private final ElasticsearchClient client;

    // =============== 写 ===============

    /** 批量写入。J4 导入、J5 采集都走这里。 */
    public void bulkInsert(List<LogEntryDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            // 构建批量请求
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (LogEntryDoc doc : docs) {
                br.operations(op -> op.index(idx -> idx
                        .index(LogIndexInitializer.INDEX)// 索引名
                        .id(doc.getId())// 文档 ID
                        .document(doc)// 文档内容
                ));
            }
            // 执行批量请求
            BulkResponse resp = client.bulk(br.build());
            if (resp.errors()) {
                // 只打前 3 条，避免刷屏
                resp.items().stream()
                        .filter(i -> i.error() != null)
                        .limit(3)
                        .forEach(i -> log.error("bulk 单条失败 id={} reason={}",
                                i.id(), i.error().reason()));
            }
        } catch (IOException e) {
            log.error("ES bulk 写入失败，条数={}", docs.size(), e);
            throw BizException.serviceUnavailable("日志写入 Elasticsearch 失败");
        }
    }

    // =============== 读 ===============

    /** 日志检索。字段名是驼峰，和 ES mapping 一一对应。 */
    public PageResult<LogEntryVO> search(String projectId, LogQueryDTO query) {
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        //过滤条件
        List<Query> must = new ArrayList<>();
        // 归属过滤永远是第一个条件——所有日志查询都必须带 projectId
        must.add(Query.of(q -> q.term(t -> t.field("projectId").value(projectId))));

        // 级别过滤
        List<String> levels = query.getLevel();

        if (levels != null && !levels.isEmpty()) {
            // 过滤掉空值
            List<FieldValue> values = levels.stream()
                    .filter(this::notBlank)
                    .map(FieldValue::of)
                    .toList();
            if (!values.isEmpty()) {
                // 构建 terms 查询
                must.add(Query.of(q -> q.terms(t -> t.field("level")
                        .terms(tv -> tv.value(values)))));
            }
        }

        // 集群 ID 过滤
        if (notBlank(query.getClusterId())) {
            must.add(Query.of(q -> q.term(t -> t.field("clusterId").value(query.getClusterId()))));
        }

        // 时间范围：from / to 是 ISO 字符串，契约里带 Z
        if (notBlank(query.getFrom()) || notBlank(query.getTo())) {
            must.add(Query.of(q -> q.range(r -> r
                    .field("timestamp")
                    .gte(notBlank(query.getFrom()) ? JsonData.of(query.getFrom()) : null)
                    .lte(notBlank(query.getTo()) ? JsonData.of(query.getTo()) : null)
            )));
        }

        // keyword 走全文检索（rawText 的 analyzer 是 ik_max_word）
        if (notBlank(query.getKeyword())) {
            must.add(Query.of(q -> q.match(m -> m.field("rawText").query(query.getKeyword()))));
        }

        try {
            SearchResponse<LogEntryVO> resp = client.search(s -> s
                            .index(LogIndexInitializer.INDEX)
                            .query(q -> q.bool(b -> b.must(must)))
                            .from(query.offset())
                            .size(size)
                            .sort(so -> so.field(f -> f.field("timestamp").order(SortOrder.Desc))),
                    LogEntryVO.class);

            long total = resp.hits().total() == null ? 0L : resp.hits().total().value();
            List<LogEntryVO> list = resp.hits().hits().stream()
                    .map(hit -> hit.source())
                    .filter(Objects::nonNull)
                    .toList();
            return PageResult.of(list, total, page, size);
        } catch (IOException e) {
            log.error("ES 检索失败 projectId={}", projectId, e);
            throw BizException.serviceUnavailable("日志检索失败，请检查 Elasticsearch");
        }
    }

    /** 单条。查不到返回 null，由调用方抛 40400。 */
    public LogEntryVO getById(String logId) {
        try {
            GetResponse<LogEntryVO> resp = client.get(
                    g -> g.index(LogIndexInitializer.INDEX).id(logId), LogEntryVO.class);
            return resp.found() ? resp.source() : null;
        } catch (IOException e) {
            log.error("ES 取单条失败 logId={}", logId, e);
            throw BizException.serviceUnavailable("读取日志失败");
        }
    }

    /**
     * 近 24h 的 ERROR 条数，给项目列表的 {@code errorCount24h} 用。
     *
     * <p>⚠️ <b>这个方法失败时返回 0，绝不抛异常。</b>
     * 项目列表页不能因为 ES 挂了就打不开——派生统计掉了就掉了。</p>
     */
    public long countError24h(String projectId) {
        String from = Instant.now().minus(24, ChronoUnit.HOURS).toString();
        try {
            return client.count(c -> c
                    .index(LogIndexInitializer.INDEX)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("projectId").value(projectId)))
                            .must(m -> m.term(t -> t.field("level").value("ERROR")))
                            .must(m -> m.range(r -> r
                                    .field("timestamp")
                                    .gte(JsonData.of(from))))))
            ).count();
        } catch (Exception e) {
            log.warn("ES countError24h 失败，降级返回 0。projectId={} 原因={}",
                    projectId, e.getMessage());
            return 0L;
        }
    }

    /** 趋势图窗口：近 24 小时（按 +08:00 对齐到整点，正好 24 个桶） */
    private static final int TREND_HOURS = 24;

    /** 三段聚合一次查完（level 分布 / 小时趋势 / Top 模板） */
    public LogStatsVO aggregate(String projectId) {
        LogStatsVO stats = new LogStatsVO();
        stats.setLevelDistribution(List.of());
        stats.setFrequencyTrend(List.of());
        stats.setTopTemplates(List.of());
        /*
         * ★ 趋势图必须给时间轴<b>固定边界</b>，否则 dateHistogram 的桶范围由命中的文档决定：
         *   一批日志全落在同一个小时里，就只返回 1 个桶，图上是一条线都画不出来的"空图"。
         *   界面标题写着"近 24h"，那范围就该是近 24h，跟这批数据多寡无关。
         *   （extendedBounds 会把没有文档的小时也补成 count=0 的桶，配合 minDocCount(0) 生效。）
         */
        ZonedDateTime nowCst = ZonedDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.HOURS);
        double fromMs = nowCst.minusHours(TREND_HOURS - 1).toInstant().toEpochMilli();
        // 上界取"当前整点 + 1ms"，不是"下一个整点"：后者会多出一个还没到点的空桶
        // （24 小时会画出 25 个点，而且首尾两个标签都叫 "21:00"，一眼就像 bug）
        double toMs = nowCst.plusHours(1).toInstant().toEpochMilli() - 1;
        try {
            SearchResponse<Void> resp = client.search(s -> s
                            .index(LogIndexInitializer.INDEX)
                            .size(0)
                            .query(q -> q.term(t -> t.field("projectId").value(projectId)))
                            .aggregations("levelDist", a -> a.terms(t -> t.field("level").size(10)))
                            .aggregations("trend", a -> a.dateHistogram(d -> d
                                    .field("timestamp")
                                    .calendarInterval(CalendarInterval.Hour)
                                    .minDocCount(0)
                                    .timeZone("+08:00")
                                    .extendedBounds(eb -> eb
                                            .min(FieldDateMath.of(f -> f.value(fromMs)))
                                            .max(FieldDateMath.of(f -> f.value(toMs))))))
                            .aggregations("topTpl", a -> a
                                    .terms(t -> t.field("clusterId").size(10))
                                    .aggregations("sample", sub -> sub.topHits(th -> th.size(1)))),
                    Void.class);

            stats.setLevelDistribution(resp.aggregations().get("levelDist").sterms()
                    .buckets().array().stream()
                    .map(b -> {
                        LogStatsVO.LevelDistributionItem item = new LogStatsVO.LevelDistributionItem();
                        item.setLevel(b.key().stringValue());
                        item.setCount(b.docCount());
                        return item;
                    }).toList());

            stats.setFrequencyTrend(resp.aggregations().get("trend").dateHistogram()
                    .buckets().array().stream()
                    .map(b -> {
                        LogStatsVO.FrequencyTrendItem item = new LogStatsVO.FrequencyTrendItem();
                        item.setTime(toHourLabel(b.keyAsString()));
                        item.setCount(b.docCount());
                        return item;
                    }).toList());

            stats.setTopTemplates(resp.aggregations().get("topTpl").sterms()
                    .buckets().array().stream()
                    .map(b -> {
                        LogStatsVO.TopTemplateItem item = new LogStatsVO.TopTemplateItem();
                        item.setClusterId(b.key().stringValue());
                        item.setCount(b.docCount());
                        b.aggregations().get("sample").topHits().hits().hits().stream().findFirst()
                                .ifPresent(hit -> {
                                    var src = hit.source();
                                    if (src != null) {
                                        item.setTemplate(src.to(LogEntryVO.class).getTemplate());
                                        item.setLevel(src.to(LogEntryVO.class).getLevel());
                                    }
                                });
                        return item;
                    }).toList());

            return stats;
        } catch (IOException e) {
            log.error("ES 聚合失败 projectId={}，返回空结构", projectId, e);
            // 返回空结构而不是抛异常：前端图表要判空渲染，接口挂着比返回空更糟
            return stats;
        }
    }

    /** "2026-09-16T08:00:00.000+08:00" → "08:00" */
    private String toHourLabel(String keyAsString) {
        if (keyAsString == null || keyAsString.length() < 13) {
            return "";
        }
        return keyAsString.substring(11, 13) + ":00";
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 批量查多个项目的 24h ERROR 数。
     * 用一次 terms 聚合按 projectId 分组，避免 N+1。
     *
     * @param projectIds 项目 ID 列表
     * @return Map<projectId, errorCount>
     */
    public Map<String, Long> batchCountError24h(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        String from = Instant.now().minus(24, ChronoUnit.HOURS).toString();
        try {
            SearchResponse<Void> resp = client.search(s -> s
                            .index(LogIndexInitializer.INDEX)
                            .size(0)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.terms(t -> t
                                            .field("projectId")
                                            .terms(tv -> tv.value(
                                                    projectIds.stream().map(FieldValue::of).toList())))
                                    )
                                    .must(m -> m.term(t -> t.field("level").value("ERROR")))
                                    .must(m -> m.range(r -> r
                                            .field("timestamp")
                                            .gte(JsonData.of(from))))
                            ))
                            .aggregations("byProject", a -> a
                                    .terms(t -> t.field("projectId").size(projectIds.size()))
                            ),
                    Void.class);

            Map<String, Long> result = new HashMap<>();
            resp.aggregations().get("byProject").sterms()
                    .buckets().array()
                    .forEach(bucket -> result.put(bucket.key().stringValue(), bucket.docCount()));

            for (String pid : projectIds) {
                result.putIfAbsent(pid, 0L);
            }
            return result;
        } catch (IOException e) {
            log.warn("ES batchCountError24h 失败，全部降级返回 0", e);
            return Map.of();
        }
    }

    /**
     * 一次 terms 聚合返回多个 cluster 的模板与条数，给新错误雷达面板用。
     *
     * <p>基线表 error_baseline 只存了 clusterId + 时间，模板和条数都在 ES 里。
     * 这里一次聚合取回，避免对每个 clusterId 各查一次（N+1）。</p>
     *
     * <p>⚠️ 失败返回空 Map，不抛异常 —— 雷达面板是派生展示，不能因为 ES 挂了一瞬就让接口 500。</p>
     */
    public Map<String, ClusterSummary> clusterSummaries(String projectId, Collection<String> clusterIds) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return Map.of();
        }
        try {
            SearchResponse<Void> resp = client.search(s -> s
                            .index(LogIndexInitializer.INDEX)
                            .size(0)
                            .query(q -> q.bool(b -> b
                                    .must(m -> m.term(t -> t.field("projectId").value(projectId)))
                                    .must(m -> m.terms(t -> t
                                            .field("clusterId")
                                            .terms(tv -> tv.value(clusterIds.stream().map(FieldValue::of).toList())))))
                            )
                            .aggregations("byCluster", a -> a
                                    .terms(t -> t.field("clusterId").size(clusterIds.size()))
                                    .aggregations("sample", sub -> sub.topHits(th -> th.size(1)))),
                    Void.class);

            Map<String, ClusterSummary> result = new HashMap<>();
            resp.aggregations().get("byCluster").sterms()
                    .buckets().array()
                    .forEach(b -> {
                        String clusterId = b.key().stringValue();
                        long count = b.docCount();
                        String template = null;
                        String sampleLogId = null;
                        var hits = b.aggregations().get("sample").topHits().hits().hits();
                        if (!hits.isEmpty() && hits.get(0).source() != null) {
                            // ★ 从 source 里取 id，而不是 hit.id()：source 是 LogEntryDoc 全量写的，
                            //   里面一定有 id 字段；hit.id() 在 top_hits 聚合场景下不可靠
                            LogEntryVO src = hits.get(0).source().to(LogEntryVO.class);
                            template = src.getTemplate();
                            sampleLogId = src.getId();
                        }
                        result.put(clusterId, new ClusterSummary(template, count, sampleLogId));
                    });
            return result;
        } catch (IOException e) {
            log.warn("ES clusterSummaries 失败，返回空。projectId={} 原因={}", projectId, e.getMessage());
            return Map.of();
        }
    }

    /** 单个错误模板的摘要：Drain3 模板 + 条数 + 一条代表日志的 document id */
    public record ClusterSummary(String template, long count, String sampleLogId) {
    }
}