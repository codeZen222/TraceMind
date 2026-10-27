package org.example.tracemind.es;

import java.util.List;

/**
 * ES 聚合查询的<b>中性</b>结果 —— 存储层只负责"从 ES 拿到什么数字"，
 * 不负责"前端图表要什么形状"。
 *
 * <h3>为什么要有它</h3>
 * {@code EsLogRepository.aggregate()} 此前直接返回 {@code LogStatsVO}（前端契约类型），
 * 于是仓储层里出现了 {@code toHourLabel("08:00")} 这种纯展示逻辑，
 * 还 import 了 {@code pojo.vo.*}。后果是双向踩踏：
 * <ul>
 *   <li>改个横轴文案 → 动到 ES DSL 所在的文件；</li>
 *   <li>想给 ES 查询裁剪字段 → 得先知道前端图表依赖哪些字段。</li>
 * </ul>
 *
 * <p>本类型只描述"聚合出来的事实"：级别计数、按小时的原始时间桶、模板计数。
 * 把 {@code keyAsString} 裁成 {@code "08:00"} 这类加工留在 service 层，那里才是视图需求的归属地。</p>
 *
 * <p>⚠️ 注意与「时区固定边界」的区别：{@code aggregate()} 里那段
 * {@code ZoneOffset.ofHours(8)} + {@code extendedBounds} <b>不是</b>展示逻辑，
 * 而是聚合正确性的必要条件（不给固定边界，一批日志全落在同一小时就只返回 1 个桶、图画不出来）。
 * 那部分应当留在仓储层。</p>
 *
 * @param levelCounts 各级别条数，按 count 降序
 * @param trendBuckets 近 24 小时趋势，每项是「ES dateHistogram 的原始时间键 + 条数」；
 *                     无数据的整点为 0（由 ES 的 minDocCount/extendedBounds 保证）
 * @param topTemplates Top 模板，按 count 降序；template/level 取自该聚类的一条样本
 */
public record AggregateResult(
        List<LevelCount> levelCounts,
        List<TrendBucket> trendBuckets,
        List<TemplateCount> topTemplates) {

    /** 某个日志级别的命中条数 */
    public record LevelCount(String level, long count) {
    }

    /**
     * 一个小时桶。
     *
     * @param keyAsString ES 原样返回的时间键，形如 {@code 2026-09-16T08:00:00.000+08:00}。
     *                    <b>不做任何格式化</b> —— 裁成 "08:00" 是展示需求，归 service 管
     */
    public record TrendBucket(String keyAsString, long count) {
    }

    /** 某个日志模板聚类的命中条数及其代表信息 */
    public record TemplateCount(String clusterId, String template, String level, long count) {
    }

    public static AggregateResult empty() {
        return new AggregateResult(List.of(), List.of(), List.of());
    }
}
