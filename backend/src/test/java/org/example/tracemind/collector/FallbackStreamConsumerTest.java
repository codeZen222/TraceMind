package org.example.tracemind.collector;

import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsFallbackStream;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底流消费者的消费语义 —— 锁住「成功 ack / 失败留 pending / 毒消息转 dead / 重试耗尽转死信」
 * 这四条分叉路径。
 *
 * <p>这里只测消费决策逻辑，不测真实 Redis（{@code EsFallbackStream} 被 mock）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FallbackStreamConsumerTest {

    private static final String STREAM = "tracemind:es:fallback";

    @Mock
    private EsLogRepository esLogRepository;
    @Mock
    private EsFallbackStream esFallbackStream;

    private TraceMindProperties properties;
    private FallbackStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new TraceMindProperties();
        consumer = new FallbackStreamConsumer(esLogRepository, esFallbackStream, properties);
    }

    private ObjectRecord<String, LogEntryDoc> record(String id, int num) {
        LogEntryDoc doc = new LogEntryDoc();
        doc.setId(id);
        doc.setProjectId("p-001");
        doc.setRawText("line-" + num);
        return StreamRecords.newRecord().in(STREAM).ofObject(doc);
    }

    @Test
    @DisplayName("★ 成功写入 → ack（了结）")
    void successAcks() {
        ObjectRecord<String, LogEntryDoc> r = record("p-001:a.log:1", 1);
        when(esFallbackStream.readNew(anyString(), any(Integer.class))).thenReturn(List.of(r));
        when(esLogRepository.bulkInsert(anyList())).thenReturn(BulkResult.allOk(List.of(r.getValue())));

        consumer.consume();

        verify(esFallbackStream).ack(eq(STREAM), any(RecordId.class));
        // 成功就不该转死信
        verify(esFallbackStream, never()).deadLetter(any());
    }

    @Test
    @DisplayName("★ 可恢复失败（ES 拒）→ 不 ack，留 pending 等重投")
    void recoverableFailureLeavesPending() {
        ObjectRecord<String, LogEntryDoc> r = record("p-001:a.log:2", 2);
        when(esFallbackStream.readNew(anyString(), any(Integer.class))).thenReturn(List.of(r));
        // bulk 返回「这一条被拒」
        when(esLogRepository.bulkInsert(anyList())).thenReturn(new BulkResult(
                List.of(), List.of(new BulkResult.Failed(r.getValue(), "mapping conflict"))));
        // delivery count 未到上限 → 不该转死信
        when(esFallbackStream.deliveryCount(any(RecordId.class))).thenReturn(1L);

        consumer.consume();

        verify(esFallbackStream, never()).ack(anyString(), any(RecordId.class));
        verify(esFallbackStream, never()).deadLetter(any());
    }

    @Test
    @DisplayName("★ 毒消息（字段残缺）→ 立刻转死信，绝不卡 pending")
    void poisonRecordDeadLetters() {
        // projectId 为 null = 业务校验必失败
        ObjectRecord<String, LogEntryDoc> r = StreamRecords.newRecord()
                .in(STREAM)
                .ofObject(new LogEntryDoc());
        when(esFallbackStream.readNew(anyString(), any(Integer.class))).thenReturn(List.of(r));

        consumer.consume();

        verify(esFallbackStream).deadLetter(any());
        // 毒消息不该走 bulk
        verify(esLogRepository, never()).bulkInsert(anyList());
    }

    @Test
    @DisplayName("★ 重试耗尽（deliveryCount 达上限）→ 转死信")
    void exhaustedRetriesDeadLetter() {
        ObjectRecord<String, LogEntryDoc> r = record("p-001:a.log:3", 3);
        when(esFallbackStream.readNew(anyString(), any(Integer.class))).thenReturn(List.of(r));
        when(esLogRepository.bulkInsert(anyList())).thenReturn(new BulkResult(
                List.of(), List.of(new BulkResult.Failed(r.getValue(), "rejected"))));
        // 投递次数已经达到上限（默认 10）
        when(esFallbackStream.deliveryCount(any(RecordId.class))).thenReturn((long) properties.getFallback().getMaxReplayAttempts());

        consumer.consume();

        verify(esFallbackStream).deadLetter(any());
    }
}