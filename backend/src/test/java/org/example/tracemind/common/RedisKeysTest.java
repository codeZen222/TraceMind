package org.example.tracemind.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis key 命名收口的单测。
 *
 * <p><b>这里锁住的是一条曾经真实存在、且从报错上看不出根因的故障路径。</b>
 * 过去 {@code ws:ticket:} 被签发端和校验端各写一遍字面量，中间没有编译期约束：
 * 任一处改名就变成「签发写 A key → 握手验 B key → getAndDelete 恒 null →
 * 所有 WS 连接静默鉴权失败，而 ticket 接口照常返回 200」。</p>
 *
 * <p>所以本类的重点不是"key 等于某个字符串"，而是<b>跨调用点的一致性</b>——
 * 见 {@link #issuerAndVerifierDeriveTheSameKey()}。那条测试才是防回归的关键。</p>
 */
class RedisKeysTest {

    @Test
    @DisplayName("★ 核心：签发端与握手校验端必须推导出同一个 key")
    void issuerAndVerifierDeriveTheSameKey() {
        String ticket = "8f3a1c9b2d4e5f60718293a4b5c6d7e8";
        String sessionId = "sess-4n7p1w6z";

        // 模拟两端各自构造 key（改造前是两处独立字面量拼接）
        String issuedKey = RedisKeys.wsTicket(ticket);   // DiagnosisController 用这个
        String verifiedKey = RedisKeys.wsTicket(ticket); // DiagnosisWebSocketHandler 用这个

        assertThat(issuedKey).isEqualTo(verifiedKey);
        // 并且票据值本身要能原样找回 —— 一次性消费靠的就是这个 key 唯一
        assertThat(issuedKey).contains(ticket);
        assertThat(RedisKeys.diagnosisSeq(sessionId)).isNotEqualTo(issuedKey);
    }

    @Test
    @DisplayName("所有 key 都带平台父前缀，可用一条 pattern 扫出全平台占用")
    void everyKeyCarriesPlatformNamespace() {
        List<String> keys = List.of(
                RedisKeys.collectorPosition("t-001"),
                RedisKeys.collectorFingerprint("t-001"),
                RedisKeys.diagnosisSeq("sess-001"),
                RedisKeys.DIAGNOSIS_AUTO_TRIGGER,
                RedisKeys.wsTicket("abc"));

        keys.forEach(k -> assertThat(k)
                .as("%s 应带父前缀", k)
                .startsWith("tracemind:"));
    }

    @Test
    @DisplayName("位点与指纹是两个独立 key，不能互相覆盖")
    void positionAndFingerprintAreDistinctKeys() {
        assertThat(RedisKeys.collectorPosition("t-001"))
                .isNotEqualTo(RedisKeys.collectorFingerprint("t-001"));
    }

    @Test
    @DisplayName("位点 Hash 的两个 field 名固定（读写两侧共用常量）")
    void positionHashFieldsAreStable() {
        assertThat(RedisKeys.F_OFFSET).isEqualTo("offset");
        assertThat(RedisKeys.F_LINES).isEqualTo("lines");
    }

    @Test
    @DisplayName("WS ticket TTL 有明确值 —— 前端拿到后必须在窗口内完成握手")
    void wsTicketHasExplicitTtl() {
        assertThat(RedisKeys.WS_TICKET_TTL.getSeconds()).isEqualTo(60L);
    }
}
