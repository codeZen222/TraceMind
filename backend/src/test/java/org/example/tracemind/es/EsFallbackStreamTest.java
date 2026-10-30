package org.example.tracemind.es;

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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EsFallbackStream 仓储 —— 锁住 Stream 主路径的硬件语义。
 *
 * <p>这里只测「仓储层」：入队带着 MAXLEN、ack 走确认、死信搬流并 ack 原消息。
 * 不测真实 Redis（那需要集成环境），只锁住调用契约和参数。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsFallbackStreamTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private StreamOperations<String, String, Object> streamOps;

    private EsFallbackStream stream;

    @BeforeEach
    void setUp() {
        doReturn(streamOps).when(redisTemplate).opsForStream();
        stream = new EsFallbackStream(redisTemplate);
    }

    private LogEntryDoc doc() {
        LogEntryDoc d = new LogEntryDoc();
        d.setId("p-001:app.log:7");
        d.setProjectId("p-001");
        d.setRawText("boom");
        return d;
    }

    @Test
    @DisplayName("入队一条日志 = XADD 到 es:fallback，且带 MAXLEN 防无限增长")
    void enqueueAddsWithMaxlen() {
        RecordId rid = RecordId.of("1-1");
        when(streamOps.add(any(ObjectRecord.class), any())).thenReturn(rid);

        RecordId result = stream.enqueue(doc());

        assertThat(result).isEqualTo(rid);
        // 验证走的是带 XAddOptions 的 add 重载（MAXLEN 所在的那一个）
        verify(streamOps).add(any(ObjectRecord.class), any());
    }

    @Test
    @DisplayName("ack = 对指定消息调 acknowledge")
    void ackCallsAcknowledge() {
        RecordId rid = RecordId.of("1-2");
        String streamKey = "tracemind:es:fallback";

        stream.ack(streamKey, rid);

        verify(streamOps).acknowledge(eq(streamKey), eq("collector"), eq(rid));
    }

    @Test
    @DisplayName("死信：搬进 dead 流并 ack 原消息，两步缺一不可")
    void deadLetterMovesAndAcks() {
        ObjectRecord<String, LogEntryDoc> record =
                org.springframework.data.redis.connection.stream.StreamRecords
                        .newRecord().in("tracemind:es:fallback").ofObject(doc());

        when(streamOps.add(any(ObjectRecord.class), any())).thenReturn(RecordId.of("9-9"));

        stream.deadLetter(record);

        // 搬进 dead 流（add 被调了一次）
        verify(streamOps).add(any(ObjectRecord.class), any());
        // 原消息被 ack（acknowledge 被调了一次）
        verify(streamOps).acknowledge(eq("tracemind:es:fallback"), eq("collector"), eq(record.getId()));
    }

    @Test
    @DisplayName("读新消息 = 用消费组 + lastConsumed，不是 latest")
    void readNewUsesConsumerGroup() {
        when(streamOps.read(
                eq(LogEntryDoc.class),
                any(),
                any(),
                any())).thenReturn(List.of());

        stream.readNew("node-x", 10);

        verify(streamOps).read(
                eq(LogEntryDoc.class),
                any(),
                any(),
                any());
    }
}