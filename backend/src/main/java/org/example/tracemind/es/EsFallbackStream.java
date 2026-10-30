package org.example.tracemind.es;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 写失败兜底流的仓储：把 {@code fallback/} 文件替换成 Redis Stream。
 *
 * <h3>一条待写日志 = 一个 entry</h3>
 * 粒度必须对齐 bulk 的逐条语义（见 {@link BulkResult}），绝不要整批塞一条——
 * 否则又回到「部分失败看不清」的老问题。
 *
 * <h3>生产 / 消费 / 死信三个出口</h3>
 * <pre>
 *   主流   tracemind:es:fallback         正常兜底、待消费重投
 *   死信   tracemind:es:fallback:dead    重试耗尽 / 毒消息，人工排查
 * </pre>
 *
 * <h3>⚠️ 与文件方案的关系</h3>
 * 本类是「主路径」。Redis 不可用时，生产端（{@code WatchServiceLogCollector}）退回
 * {@code writeToLocalFallback} 文件方案作为最后防线；文件方案（{@code FallbackReplayJob}）
 * 仍然保留并继续工作。两条路径并存，不删文件逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsFallbackStream {

    private final RedisTemplate<String, Object> streamRedisTemplate;

    private StreamOperations<String, String, Object> ops() {
        return streamRedisTemplate.opsForStream();
    }

    // ==================================================================
    // 生产端
    // ==================================================================

    /**
     * 把一条失败日志写进兜底流（等价于原来的「落盘到 fallback/」）。
     *
     * <p>调用方负责 try/catch：Redis 不可用时本方法抛异常，调用方退回文件方案。</p>
     */
    public RecordId enqueue(LogEntryDoc doc) {
        String stream = RedisKeys.esFallbackStream();

        ObjectRecord<String, LogEntryDoc> record = StreamRecords
                .newRecord()
                .in(stream)
                .ofObject(doc);

        // ★ MAXLEN 防无限增长 —— 文件方案缺的那层容量保护
        RecordId id = ops().add(record, RedisStreamCommands.XAddOptions.maxlen(10000L));
        log.debug("兜底流入队 stream={} id={} docId={}", stream, id, doc.getId());
        return id;
    }

    // ==================================================================
    // 消费端
    // ==================================================================

    /**
     * 按消费者组读一批待重放日志（只读新消息，{@code >}）。
     *
     * <p>消费者组有自己独立的游标，用 {@link ReadOffset#lastConsumed()} 走组内游标；
     * 别用 {@code latest()}，那会跳过组内的 pending 消息。</p>
     */
    public List<ObjectRecord<String, LogEntryDoc>> readNew(String consumerName, int count) {
        String stream = RedisKeys.esFallbackStream();
        List<ObjectRecord<String, LogEntryDoc>> records = ops().read(
                LogEntryDoc.class,
                Consumer.from(RedisKeys.ES_FALLBACK_GROUP, consumerName),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                        .count(count)
                        .block(Duration.ofSeconds(2)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records == null ? List.of() : records;
    }

    /**
     * 重读<b>本消费者自己</b>还没 ack 的消息（pending），用于「上一轮失败了、这轮再试」。
     *
     * <p>读 offset 用 {@code 0}（从 PEL 头开始），而不是新消息的 {@code >}。</p>
     */
    public List<ObjectRecord<String, LogEntryDoc>> readOwnPending(String consumerName, int count) {
        String stream = RedisKeys.esFallbackStream();
        List<ObjectRecord<String, LogEntryDoc>> records = ops().read(
                LogEntryDoc.class,
                Consumer.from(RedisKeys.ES_FALLBACK_GROUP, consumerName),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                        .count(count)
                        .block(Duration.ofSeconds(2)),
                StreamOffset.create(stream, ReadOffset.from("0")));
        return records == null ? List.of() : records;
    }

    /** 确认一条已成功写进 ES（等价于原来的「成功即删文件」）。 */
    public void ack(String stream, RecordId... ids) {
        ops().acknowledge(stream, RedisKeys.ES_FALLBACK_GROUP, ids);
    }

    /**
     * 毒消息 / 重试耗尽的消息转死信流，并 ack 原消息。
     *
     * <p>⚠️ 两步缺一不可：只搬不 ack 会让原消息永久卡 pending 头；只 ack 不搬 = 数据丢失。</p>
     */
    public void deadLetter(ObjectRecord<String, LogEntryDoc> record) {
        String deadStream = RedisKeys.esFallbackDeadStream();

        ObjectRecord<String, LogEntryDoc> dead = StreamRecords
                .newRecord()
                .in(deadStream)
                .ofObject(record.getValue());
        // 死信流也设 MAXLEN，防止无限增长
        ops().add(dead, RedisStreamCommands.XAddOptions.maxlen(1000L));

        ack(record.getStream(), record.getId());
        log.error("兜底消息转死信 stream={} id={} docId={}",
                record.getStream(), record.getId(), record.getValue().getId());
    }

    // ==================================================================
    // 初始化 / 可观测
    // ==================================================================

    /** 初始化消费组：必须在消费者开读之前建好，且幂等（组已存在会抛 BUSYGROUP，忽略）。 */
    public void ensureGroup() {
        try {
            ops().createGroup(RedisKeys.esFallbackStream(), RedisKeys.ES_FALLBACK_GROUP);
        } catch (Exception e) {
            log.debug("消费组已存在，跳过创建：{}", RedisKeys.ES_FALLBACK_GROUP);
        }
    }

    /** 积压多少条没确认（可接 Prometheus）。 */
    public long pendingCount() {
        PendingMessagesSummary summary = ops().pending(
                RedisKeys.esFallbackStream(), RedisKeys.ES_FALLBACK_GROUP);
        return summary.getTotalPendingMessages();
    }

    /**
     * 查一条消息被投递了几次，用于死信判定（重试耗尽）。
     *
     * <p>走 {@code XPENDING stream group - + count} 明细，逐条找 id，
     * 读 {@link PendingMessage#getTotalDeliveryCount()}。</p>
     */
    public long deliveryCount(RecordId id) {
        String stream = RedisKeys.esFallbackStream();
        PendingMessages pending = ops().pending(
                stream, RedisKeys.ES_FALLBACK_GROUP, Range.unbounded(), 1000L);
        if (pending == null) {
            return 0;
        }
        for (PendingMessage msg : pending) {
            if (msg.getId().equals(id)) {
                return msg.getTotalDeliveryCount();
            }
        }
        return 0;
    }

    /**
     * 崩溃接管：抢回「闲置超过 minIdle、已被别的实例读走但没 ack」的消息。
     * 消费者启动时调用一次，把上一个实例遗留的 pending 转给自己。
     */
    public void claimStale(String newConsumerName, Duration minIdle) {
        try {
            ops().claim(
                    RedisKeys.esFallbackStream(),
                    RedisKeys.ES_FALLBACK_GROUP,
                    newConsumerName,
                    minIdle,
                    RecordId.of("0-0"));
        } catch (Exception e) {
            log.warn("XAUTOCLAIM 抢占失败（可能无遗留 pending）{}", e.getMessage());
        }
    }
}