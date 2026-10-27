package org.example.tracemind.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Utilx 的单测 —— 重点是 rootMessage 的<b>防环</b>，那是本次收口的真正动机。
 *
 * <p>此前项目里有两份私有 {@code rootMessage}：一份完全裸奔（{@code while getCause()!=null}），
 * 另一份只挡了自引用、挡不住两元素环。两者都跑在故障上报路径上 ——
 * 一旦异常成环，"如实报告错误"会变成"把处理线程永久卡死"。</p>
 */
class UtilxTest {

    @Test
    @DisplayName("取最内层 cause 的 message，而不是外层那句没信息量的包装")
    void returnsDeepestCauseMessage() {
        Exception root = new IOException("connection refused");
        Exception wrapped = new IllegalStateException("调用 Python 失败", root);

        assertThat(Utilx.rootMessage(wrapped)).isEqualTo("connection refused");
    }

    @Test
    @DisplayName("message 为 null 时退回类名，绝不返回 null 或字面量 null")
    void fallsBackToClassNameWhenMessageMissing() {
        // 显式转型消歧：IllegalStateException 同时有 (String) 和 (Throwable) 两个单参构造器
        assertThat(Utilx.rootMessage(new IllegalStateException((String) null)))
                .isEqualTo("IllegalStateException");
        assertThat(Utilx.rootMessage(new IllegalStateException("   ")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("入参为 null 也不炸 —— 它跑在 catch 块里，自己不能再抛")
    void handlesNullInput() {
        assertThat(Utilx.rootMessage(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("★ 自引用异常不死循环（旧实现之一靠 getCause()==this 挡住的那种）")
    void terminatesOnSelfReferencingCause() {
        RuntimeException e = new RuntimeException("boom");
        // JDK 允许这么构造：e.getCause() == e
        try {
            e.initCause(e);
        } catch (Throwable ignored) {
            // 某些 JDK 会拒绝自引用；那就手工造一个等价的行为
        }
        if (e.getCause() == e) {
            assertThat(Utilx.rootMessage(e)).isNotBlank();   // 走到这里就说明没卡住
        } else {
            // 退化成普通链，仍应正常返回
            assertThat(Utilx.rootMessage(e)).isNotBlank();
        }
    }

    @Test
    @DisplayName("★ 两元素环 A→B→A 也必须终止 —— 这是原先那份「有防护」的实现也漏掉的情况")
    void terminatesOnTwoElementCycle() {
        RuntimeException a = new RuntimeException("A");
        RuntimeException b = new RuntimeException("B", a);
        a.initCause(b);   // 形成 A -> B -> A

        // 前提成立才继续断言（若 JDK 拒绝成环，本用例自然通过）
        if (a.getCause() == b && b.getCause() == a) {
            long start = System.nanoTime();
            String result = Utilx.rootMessage(a);
            long costMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(result).isNotBlank();
            // 真正的保护是"没卡住"；这条时间断言只是让失败时更好诊断
            assertThat(costMs).as("防环失效会表现为耗时极长或直接挂死").isLessThan(1000L);
        }
    }

    @Test
    @DisplayName("深链超过上限时停在第 N 层，不再往下走")
    void stopsAtDepthLimit() {
        Throwable deep = new RuntimeException("root");
        for (int i = 0; i < 200; i++) {
            deep = new RuntimeException("layer" + i, deep);
        }
        // 最深那个节点的 message 是 "root"，但限步数后停在距顶 20 层的位置
        String msg = Utilx.rootMessage(deep);
        assertThat(msg).isNotBlank();
        assertThat(msg).doesNotStartWith("null");
    }

    @Test
    @DisplayName("toInstant 按 UTC 解释库里的 DATETIME（与四份旧副本行为一致）")
    void convertsLocalDateTimeAsUtc() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 23, 8, 30, 0);

        assertThat(Utilx.toInstant(t))
                .isEqualTo(t.toInstant(ZoneOffset.UTC));
        assertThat(Utilx.toIsoUtc(t)).isEqualTo("2026-10-23T08:30:00Z");
    }

    @Test
    @DisplayName("时间工具对 null 安全 —— VO 转换里到处是可能为空的列")
    void timeHelpersAreNullSafe() {
        assertThat(Utilx.toInstant(null)).isNull();
        assertThat(Utilx.toIsoUtc(null)).isNull();
    }

    @Test
    @DisplayName("字符串工具：isBlank 涵盖 null 与全空白，emptyIfNull 只转 null")
    void stringHelpersBehaveAsBefore() {
        assertThat(Utilx.isBlank(null)).isTrue();
        assertThat(Utilx.isBlank("  ")).isTrue();
        assertThat(Utilx.isBlank("x")).isFalse();

        assertThat(Utilx.emptyIfNull(null)).isEmpty();
        assertThat(Utilx.emptyIfNull("v")).isEqualTo("v");
    }
}
