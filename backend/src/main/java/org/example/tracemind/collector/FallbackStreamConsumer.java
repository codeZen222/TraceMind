package org.example.tracemind.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsFallbackStream;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

/**
 * 兜底流的消费者：定时把 {@code tracemind:es:fallback} 里待重放的日志写回 ES。
 *
 * <p>它是 {@link EsFallbackStream} 的消费端，与文件方案 {@link FallbackReplayJob} 并存：
 * 文件方案处理「Redis 挂时生产端退回的本地文件」，本类处理「Stream 里的待重放消息」。
 * 两者不冲突 —— Stream 是主路径，文件是最后防线。</p>
 *
 * <h3>消费失败的二分处置（本类的地基）</h3>
 * <pre>
 *   处理消息
 *     ├─ 成功              → ack（了结）
 *     ├─ 可恢复失败（ES 拒/超时）→ 不 ack，留 pending 等重投；重投满上限 → 死信
 *     └─ 毒消息（反序列化失败）   → 立刻 ack + 转死信（绝不卡 pending 队头）
 * </pre>
 *
 * <h3>⚠️ 为什么毒消息必须立刻 ack</h3>
 * 消费者组按 pending 顺序读，一条坏消息若「处理失败 → 不 ack」，它永远卡队头，
 * 后面的好消息全部瘫痪。所以「永久失败」和「可恢复失败」必须分开处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackStreamConsumer {

    /** 每轮最多读多少条，防止一轮消费撑满内存 */
    private static final int BATCH = 100;

    /** 单条消息被投递超过这个次数仍失败 → 转死信（复用文件方案的同一语义值） */
    private final EsLogRepository esLogRepository;
    private final EsFallbackStream esFallbackStream;
    private final TraceMindProperties properties;

    /** 本实例的消费者名：用 hostname，保证同组内各实例可区分、崩溃后可被 XAUTOCLAIM 接管 */
    private final String consumerName = resolveConsumerName();

    private static String resolveConsumerName() {
        try {
            return "node-" + InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "node-unknown";
        }
    }

    /**
     * 每次执行：先确保消费组存在、并接管上一实例遗留的 pending，再消费一批新消息。
     *
     * <p>{@code fixedDelay} 表示上一轮跑完再等 60 秒，不会自我重叠。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void consume() {
        try {
            esFallbackStream.ensureGroup();
            esFallbackStream.claimStale(consumerName, Duration.ofSeconds(60));
        } catch (Exception e) {
            log.warn("准备消费组/接管 pending 失败：{}", e.getMessage());
            return;
        }

        List<ObjectRecord<String, LogEntryDoc>> records =
                esFallbackStream.readNew(consumerName, BATCH);

        if (records.isEmpty()) {
            return;
        }

        for (ObjectRecord<String, LogEntryDoc> record : records) {
            handleOne(record);
        }
    }

    // ==================================================================
    // 单条消息的处置
    // ==================================================================

    private void handleOne(ObjectRecord<String, LogEntryDoc> record) {
        // 反序列化失败 = 毒消息（版本演进导致的兼容问题），立刻转死信
        LogEntryDoc doc;
        try {
            doc = record.getValue();
            if (doc == null || doc.getId() == null || doc.getProjectId() == null) {
                // 字段残缺：业务校验必失败，归为毒消息
                log.error("兜底流消息字段残缺，转死信 id={}", record.getId());
                esFallbackStream.deadLetter(record);
                return;
            }
        } catch (Exception e) {
            log.error("兜底流消息反序列化失败（毒消息），转死信 id={}", record.getId(), e);
            esFallbackStream.deadLetter(record);
            return;
        }

        try {
            BulkResult result = esLogRepository.bulkInsert(List.of(doc));

            if (result.allSucceeded()) {
                esFallbackStream.ack(record.getStream(), record.getId());
                return;
            }
            // 部分失败（这里单条即「这一条被拒」）：可恢复，不 ack
            String reason = result.failed().isEmpty() ? "未知" : result.failed().get(0).reason();
            log.warn("兜底流重放被拒，留 pending 重试 id={} docId={} reason={}",
                    record.getId(), doc.getId(), reason);
            deadLetterIfExhausted(record);

        } catch (Exception e) {
            // ES 不可用等可恢复异常：不 ack，等 Stream 重投
            log.warn("兜底流重放暂时失败，留 pending 重试 id={} docId={} 原因={}",
                    record.getId(), doc.getId(), e.getMessage());
            deadLetterIfExhausted(record);
        }
    }

    /**
     * 重试耗尽判定：投递次数达到上限仍未成功 → 转死信 + 告警，不再无限重投。
     *
     * <p>在「可恢复失败」后调用 —— 毒消息走的是直接转死信（在 handleOne 开头），
     * 不经过这里。两类失败的死信路径是分开的。</p>
     */
    private void deadLetterIfExhausted(ObjectRecord<String, LogEntryDoc> record) {
        int limit = properties.getFallback().getMaxReplayAttempts();
        long deliveryCount = esFallbackStream.deliveryCount(record.getId());
        if (deliveryCount >= limit) {
            log.error("★ 兜底流消息重试已达上限 {} 次仍写不进 ES，转死信等待人工处理 id={} docId={}",
                    limit, record.getId(), record.getValue().getId());
            esFallbackStream.deadLetter(record);
        }
    }
}