package org.example.tracemind.es;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 文档 ID 的单一真源 —— 锁住 R-2.3 的统一规则。
 *
 * <p>此前同一个 ID 有三份独立实现（采集拼绝对路径、导入拼文件名、报告再手工拆），
 * 「同一行给出 {@code :509} / {@code :510}」的事故就是这么来的。本类的价值在于：
 * <b>只要两条链路都调它，规则就不可能再分叉。</b></p>
 */
class EsDocIdTest {

    @Nested
    @DisplayName("生成：两条链路必须产出同形 ID")
    class Generation {

        @Test
        @DisplayName("★ 采集传绝对路径、导入传裸文件名 → 结果必须完全一致")
        void sameFileSameIdRegardlessOfInputForm() {
            String fromCollector = EsDocId.of("p-001", "C:/logs/app.log", 510);
            String fromImport = EsDocId.of("p-001", "app.log", 510);

            assertThat(fromCollector)
                    .as("中间段归一为裸文件名，这是本次统一的核心")
                    .isEqualTo(fromImport)
                    .isEqualTo("p-001:app.log:510");
        }

        @Test
        @DisplayName("反斜杠路径同样归一（Windows 真实形态）")
        void normalizesBackslashPath() {
            assertThat(EsDocId.of("p-001", "C:\\logs\\sub\\app.log", 7))
                    .isEqualTo("p-001:app.log:7");
        }

        @Test
        @DisplayName("★ ID 里恰好只有两个冒号 —— 盘符冒号不再污染分隔符")
        void containsExactlyTwoSeparators() {
            String id = EsDocId.of("p-001", "C:/logs/app.log", 1);

            // 改造前这里会是 4 个冒号（C: + 两个段边界），split(":") 必然截断在 C
            assertThat(id.chars().filter(c -> c == ':').count()).isEqualTo(2);
        }

        @Test
        @DisplayName("行号原样进末段，不做任何偏移（1 基约定由调用方保证）")
        void keepsLineNumberVerbatim() {
            assertThat(EsDocId.of("p-001", "app.log", 1)).endsWith(":1");
            assertThat(EsDocId.of("p-001", "app.log", 5001)).endsWith(":5001");
        }
    }

    @Nested
    @DisplayName("解析：报告侧的反向拆解")
    class Parsing {

        @Test
        @DisplayName("去掉 projectId 段，留下给人看的 fileName:line")
        void stripsProjectSegment() {
            assertThat(EsDocId.withoutProject("p-001:app.log:510")).isEqualTo("app.log:510");
        }

        @Test
        @DisplayName("★ 新格式下剩余部分不含冒号，可安全整体保留")
        void remainderHasNoSeparatorAnymore() {
            String rest = EsDocId.withoutProject(EsDocId.of("p-001", "C:/logs/app.log", 9));

            assertThat(rest).isEqualTo("app.log:9");
            assertThat(rest.indexOf(':')).isEqualTo(rest.lastIndexOf(':'));
        }

        @Test
        @DisplayName("脏输入原样返回，绝不抛异常打断报告生成")
        void returnsInputOnMalformedId() {
            assertThat(EsDocId.withoutProject("no-separator")).isEqualTo("no-separator");
            assertThat(EsDocId.withoutProject("p-001:")).isEqualTo("p-001:");
            assertThat(EsDocId.withoutProject(null)).isNull();
            assertThat(EsDocId.withoutProject("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("校验与边界")
    class Validation {

        @Test
        @DisplayName("isWellFormed 认三段结构")
        void checksThreeSegmentShape() {
            assertThat(EsDocId.isWellFormed("p-001:app.log:12")).isTrue();
            assertThat(EsDocId.isWellFormed("app.log:12")).isFalse();   // 缺 projectId
            assertThat(EsDocId.isWellFormed("p-001:app.log")).isFalse(); // 缺行号
            assertThat(EsDocId.isWellFormed(":x:1")).isFalse();          // projectId 为空
            assertThat(EsDocId.isWellFormed(null)).isFalse();
            assertThat(EsDocId.isWellFormed("")).isFalse();
        }

        @Test
        @DisplayName("bareFileName 对 null/空串返回空，不抛")
        void bareFileNameIsNullSafe() {
            assertThat(EsDocId.bareFileName(null)).isEmpty();
            assertThat(EsDocId.bareFileName("  ")).isEmpty();
            assertThat(EsDocId.bareFileName("app.log")).isEqualTo("app.log");
            assertThat(EsDocId.bareFileName("/var/log/app.log")).isEqualTo("app.log");
        }

        @Test
        @DisplayName("★ 已知取舍：不同目录的同名文件会撞 ID（当前一个项目只有一个 logPath，不会发生）")
        void documentsTheSameNameCollisionTradeoff() {
            // 这不是 bug 而是被明确记录的设计取舍，见 EsDocId 类注释。
            // 写成断言是为了让这个决定"可见"：将来支持多文件监听时，这条会提醒改规则的人。
            assertThat(EsDocId.of("p-001", "/a/app.log", 1))
                    .isEqualTo(EsDocId.of("p-001", "/b/app.log", 1));
        }
    }
}
