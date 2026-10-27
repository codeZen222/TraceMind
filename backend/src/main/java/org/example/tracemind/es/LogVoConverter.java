package org.example.tracemind.es;

import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;

import java.util.List;

/**
 * ES 数据形状 → 前端契约的<b>唯一</b>转换处。
 *
 * <h3>为什么存在</h3>
 * {@link EsLogRepository} 此前直接返回 {@code LogEntryVO} / {@code LogStatsVO}，
 * 于是仓储层里混进了 {@code "08:00"} 这种纯展示格式化 —— 存储层与视图层互相踩踏：
 * 改横轴文案要动 ES DSL 所在的文件；给 ES 裁剪字段又得先知道前端依赖哪些字段。
 *
 * <p>本类把"面向前端的那一半"收拢过来：仓储只出中性事实（{@link LogEntryDoc} /
 * {@link AggregateResult}），这里负责变成 VO。对比同项目的 {@code mapper} 包
 * （9 个文件只 import {@code pojo.entity}），ES 这层现在与之对齐了。</p>
 */
public final class LogVoConverter {

    private LogVoConverter() {
    }

    /**
     * 一条日志文档 → 响应体。
     *
     * <p>字段名与类型完全一致（已逐字段核对），所以是纯机械拷贝。
     * ⚠️ 若将来两边出现分歧（比如 VO 加派生字段），必须在这里显式处理，
     * 不要偷偷改仓储层的返回类型。</p>
     */
    public static LogEntryVO toVO(LogEntryDoc doc) {
        if (doc == null) {
            return null;
        }
        LogEntryVO vo = new LogEntryVO();
        vo.setId(doc.getId());
        vo.setProjectId(doc.getProjectId());
        vo.setTimestamp(doc.getTimestamp());
        vo.setLevel(doc.getLevel());
        vo.setRawText(doc.getRawText());
        vo.setTemplate(doc.getTemplate());
        vo.setClusterId(doc.getClusterId());
        vo.setFilePath(doc.getFilePath());
        vo.setLineNumber(doc.getLineNumber());
        vo.setStackTrace(doc.getStackTrace());
        return vo;
    }

    /**
     * 三段聚合结果 → 前端图表结构。
     *
     * <p>{@code frequencyTrend.time} 要的是 {@code "08:00"} 这种横轴标签，
     * 而 ES 给的是完整时间键 —— 这个裁剪属于视图需求，所以放在这里而不是仓储层。</p>
     */
    public static LogStatsVO toStatsVO(AggregateResult result) {
        LogStatsVO stats = new LogStatsVO();
        if (result == null) {
            // 刻意用空数组而不是 null：前端图表按"判空渲染"实现，给 null 会直接崩
            stats.setLevelDistribution(List.of());
            stats.setFrequencyTrend(List.of());
            stats.setTopTemplates(List.of());
            return stats;
        }

        stats.setLevelDistribution(result.levelCounts().stream().map(c -> {
            LogStatsVO.LevelDistributionItem item = new LogStatsVO.LevelDistributionItem();
            item.setLevel(c.level());
            item.setCount(c.count());
            return item;
        }).toList());

        stats.setFrequencyTrend(result.trendBuckets().stream().map(b -> {
            LogStatsVO.FrequencyTrendItem item = new LogStatsVO.FrequencyTrendItem();
            item.setTime(toHourLabel(b.keyAsString()));
            item.setCount(b.count());
            return item;
        }).toList());

        stats.setTopTemplates(result.topTemplates().stream().map(t -> {
            LogStatsVO.TopTemplateItem item = new LogStatsVO.TopTemplateItem();
            item.setClusterId(t.clusterId());
            item.setTemplate(t.template());
            item.setLevel(t.level());
            item.setCount(t.count());
            return item;
        }).toList());

        return stats;
    }

    /**
     * "2026-09-16T08:00:00.000+08:00" → "08:00"。
     *
     * <p>⚠️ 用<b>定位</b>而非固定下标取小时：原实现是 {@code substring(11,13)}，
     * 依赖"时区偏移永远在第 11 位之后"这一脆弱前提。改成找 {@code 'T'} 更稳。</p>
     */
    static String toHourLabel(String keyAsString) {
        if (keyAsString == null) {
            return "";
        }
        int t = keyAsString.indexOf('T');
        // 需要 T 之后至少 6 个字符（HH:mm），否则第二处 substring 会越界
        if (t < 0 || t + 7 > keyAsString.length()) {
            return "";
        }
        // T 后紧跟 HH:mm
        return keyAsString.substring(t + 1, t + 3) + ":" + keyAsString.substring(t + 4, t + 6);
    }
}
