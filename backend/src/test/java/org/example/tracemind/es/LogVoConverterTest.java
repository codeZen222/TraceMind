package org.example.tracemind.es;

import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.example.tracemind.pojo.vo.LogStatsVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 数据形状 → 前端契约的转换 —— R-4.4 的验收标准。
 *
 * <p>这层存在的意义是「仓储只出中性事实、视图需求归 service」，所以测试重点有两个：
 * ① 字段一个都不能在搬运中丢失；② 属于展示的加工（横轴标签）在这里而不在仓储层。</p>
 */
class LogVoConverterTest {

    private LogEntryDoc doc() {
        LogEntryDoc d = new LogEntryDoc();
        d.setId("p-001:app.log:7");
        d.setProjectId("p-001");
        d.setTimestamp("2026-09-16T08:00:00.512Z");
        d.setLevel("ERROR");
        d.setRawText("java.lang.NullPointerException");
        d.setTemplate("java.lang.NullPointerException at *");
        d.setClusterId("cluster_0");
        d.setFilePath("C:/logs/app.log");
        d.setLineNumber(7);
        d.setStackTrace("at OrderService.getAddress(OrderService.java:100)");
        return d;
    }

    @Nested
    @DisplayName("单条文档 → VO")
    class ToVO {

        @Test
        @DisplayName("★ 十个字段全部搬运，一个都不能漏")
        void copiesEveryField() {
            LogEntryDoc d = doc();
            LogEntryVO vo = LogVoConverter.toVO(d);

            // 逐字段断言而不是整体 equals：漏搬哪个能一眼看出是哪行
            assertThat(vo.getId()).isEqualTo(d.getId());
            assertThat(vo.getProjectId()).isEqualTo(d.getProjectId());
            assertThat(vo.getTimestamp()).isEqualTo(d.getTimestamp());
            assertThat(vo.getLevel()).isEqualTo(d.getLevel());
            assertThat(vo.getRawText()).isEqualTo(d.getRawText());
            assertThat(vo.getTemplate()).isEqualTo(d.getTemplate());
            assertThat(vo.getClusterId()).isEqualTo(d.getClusterId());
            assertThat(vo.getFilePath()).isEqualTo(d.getFilePath());
            assertThat(vo.getLineNumber()).isEqualTo(d.getLineNumber());
            assertThat(vo.getStackTrace()).isEqualTo(d.getStackTrace());
        }

        @Test
        @DisplayName("null 入参返回 null，不抛 —— getById 查不到时就是这个形状")
        void handlesNull() {
            assertThat(LogVoConverter.toVO(null)).isNull();
        }
    }

    @Nested
    @DisplayName("聚合结果 → 图表结构")
    class ToStatsVO {

        @Test
        @DisplayName("三段都正确映射，趋势时间被裁成横轴标签")
        void mapsAllThreeSections() {
            AggregateResult r = new AggregateResult(
                    List.of(new AggregateResult.LevelCount("ERROR", 13L)),
                    List.of(new AggregateResult.TrendBucket("2026-09-16T08:00:00.000+08:00", 8L)),
                    List.of(new AggregateResult.TemplateCount("cluster_0", "NPE at *", "ERROR", 17L)));

            LogStatsVO stats = LogVoConverter.toStatsVO(r);

            assertThat(stats.getLevelDistribution())
                    .extracting(LogStatsVO.LevelDistributionItem::getLevel,
                            LogStatsVO.LevelDistributionItem::getCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("ERROR", 13L));

            assertThat(stats.getFrequencyTrend())
                    .extracting(LogStatsVO.FrequencyTrendItem::getTime,
                            LogStatsVO.FrequencyTrendItem::getCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("08:00", 8L));

            assertThat(stats.getTopTemplates())
                    .extracting(LogStatsVO.TopTemplateItem::getClusterId,
                            LogStatsVO.TopTemplateItem::getTemplate,
                            LogStatsVO.TopTemplateItem::getLevel,
                            LogStatsVO.TopTemplateItem::getCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("cluster_0", "NPE at *", "ERROR", 17L));
        }

        @Test
        @DisplayName("★ null / 空结果都要回空数组而不是 null —— 前端图表按判空渲染")
        void neverReturnsNullLists() {
            for (AggregateResult in : new AggregateResult[]{null, AggregateResult.empty()}) {
                LogStatsVO stats = LogVoConverter.toStatsVO(in);
                assertThat(stats.getLevelDistribution()).isNotNull().isEmpty();
                assertThat(stats.getFrequencyTrend()).isNotNull().isEmpty();
                assertThat(stats.getTopTemplates()).isNotNull().isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("横轴标签裁剪（从仓储层挪过来的那段视图逻辑）")
    class HourLabel {

        @Test
        @DisplayName("标准 ES 时间键 → HH:mm")
        void cutsStandardKey() {
            assertThat(LogVoConverter.toHourLabel("2026-09-16T08:00:00.000+08:00")).isEqualTo("08:00");
            assertThat(LogVoConverter.toHourLabel("2026-09-16T21:00:00.000+08:00")).isEqualTo("21:00");
            assertThat(LogVoConverter.toHourLabel("2026-09-16T00:00:00.000+08:00")).isEqualTo("00:00");
        }

        @Test
        @DisplayName("★ 不再依赖固定下标：偏移量位数变化也不会错位")
        void robustToOffsetWidth() {
            // 旧实现 substring(11,13) 在这些形态上仍然对，但纯属侥幸；新实现按 'T' 定位
            assertThat(LogVoConverter.toHourLabel("2026-09-16T13:45:00Z")).isEqualTo("13:45");
            assertThat(LogVoConverter.toHourLabel("2026-09-16T09:00:00.000+00:00")).isEqualTo("09:00");
        }

        @Test
        @DisplayName("脏输入返回空串而不是抛异常 —— 一个坏桶不该毁掉整张图")
        void degradesOnMalformedInput() {
            assertThat(LogVoConverter.toHourLabel(null)).isEmpty();
            assertThat(LogVoConverter.toHourLabel("")).isEmpty();
            assertThat(LogVoConverter.toHourLabel("no-T-here")).isEmpty();
            assertThat(LogVoConverter.toHourLabel("2026-09-16T1")).isEmpty();   // 截断
        }
    }

    @Test
    @DisplayName("PageResult.map 换类型但保留分页元信息")
    void pageResultMapKeepsMetadata() {
        PageResult<LogEntryDoc> src = PageResult.of(List.of(doc(), doc()), 137L, 2, 20);

        PageResult<LogEntryVO> mapped = src.map(LogVoConverter::toVO);

        assertThat(mapped.getList()).hasSize(2);
        assertThat(mapped.getList().get(0).getLevel()).isEqualTo("ERROR");
        assertThat(mapped.getTotal()).isEqualTo(137L);
        assertThat(mapped.getPage()).isEqualTo(2);
        assertThat(mapped.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("map 对 list 为 null 的历史对象也安全")
    void pageResultMapHandlesNullList() {
        PageResult<LogEntryDoc> src = new PageResult<>(null, 0L, 1, 20);

        assertThat(src.map(LogVoConverter::toVO).getList()).isNotNull().isEmpty();
    }
}
