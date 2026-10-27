package org.example.tracemind.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.pojo.entity.AgentEvent;
import org.example.tracemind.pojo.vo.DiagnosisEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 诊断事件流水线的顺序与失败语义 —— 这三条是回放正确性的地基。
 *
 * <p>拆出来之前，要验证这些行为得凑齐 {@code DiagnosisServiceImpl} 的 11 个协作者；
 * 现在只需 Redis + Mapper + Registry 三个。<b>这正是本次拆分的收益证明。</b></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosisEventPipelineTest {

    private static final String SESSION_ID = "sess-4n7p1w6z";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AgentEventMapper agentEventMapper;
    @Mock
    private WsSessionRegistry wsSessionRegistry;

    private DiagnosisEventPipeline pipeline;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        pipeline = new DiagnosisEventPipeline(redis, agentEventMapper, wsSessionRegistry, new ObjectMapper());
    }

    private DiagnosisEventVO stageEvent() {
        DiagnosisEventVO e = new DiagnosisEventVO();
        e.setType("stage");
        e.setStage(1);
        e.setName("模板解析");
        return e;
    }

    @Test
    @DisplayName("★ 铁律顺序：先 INCR 发号 → 再落 agent_event → 最后推 WS")
    void followsIncrThenPersistThenPushOrder() {
        when(valueOps.increment(anyString())).thenReturn(7L);

        Integer seq = pipeline.publish(SESSION_ID, stageEvent());

        assertThat(seq).isEqualTo(7);

        // Mockito InOrder 是唯一能真正锁住"顺序"的手段 —— verify 单独调用只证明发生过
        InOrder ordered = inOrder(valueOps, agentEventMapper, wsSessionRegistry);
        ordered.verify(valueOps).increment(anyString());
        ordered.verify(agentEventMapper).insert(any(AgentEvent.class));
        ordered.verify(wsSessionRegistry).send(anyString(), any());
        ordered.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("落库的记录带正确 seq、事件类型与序列化后的 payload")
    void persistsRowWithSeqAndPayload() {
        when(valueOps.increment(anyString())).thenReturn(3L);

        pipeline.publish(SESSION_ID, stageEvent());

        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventMapper).insert(captor.capture());
        AgentEvent row = captor.getValue();

        assertThat(row.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(row.getSeq()).isEqualTo(3);
        assertThat(row.getEventType()).isEqualTo("stage");
        assertThat(row.getPayload()).contains("\"type\":\"stage\"");
    }

    @Test
    @DisplayName("★ Redis 挂了：整条事件丢弃，既不落库也不推送（宁缺勿乱序）")
    void dropsEventWhenSeqGenerationFails() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        Integer seq = pipeline.publish(SESSION_ID, stageEvent());

        assertThat(seq).as("返回 null 表示被丢弃").isNull();
        verify(agentEventMapper, never()).insert(any());
        verify(wsSessionRegistry, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("★ 落库失败仍然推送：让它可见比让它完美重要（代价只是不进历史回放）")
    void stillPushesWhenPersistenceFails() {
        when(valueOps.increment(anyString())).thenReturn(5L);
        when(agentEventMapper.insert(any())).thenThrow(new RuntimeException("db down"));

        Integer seq = pipeline.publish(SESSION_ID, stageEvent());

        assertThat(seq).isEqualTo(5);
        verify(wsSessionRegistry).send(anyString(), any());
    }

    @Test
    @DisplayName("推送抛错不影响已完成的落库")
    void pushFailureDoesNotRollBackPersistence() {
        when(valueOps.increment(anyString())).thenReturn(9L);
        // send 是 void 方法，打桩只能用 doThrow（when(...) 编译期就报"此处不允许使用空类型"）
        org.mockito.Mockito.doThrow(new RuntimeException("ws broken"))
                .when(wsSessionRegistry).send(anyString(), any());

        try {
            pipeline.publish(SESSION_ID, stageEvent());
        } catch (RuntimeException expected) {
            // send 的异常按当前实现会外抛；这里只关心落库已经发生
        }

        verify(agentEventMapper).insert(any(AgentEvent.class));
    }

    @Test
    @DisplayName("resetSequence 失败不阻断 —— 它是优化项，发号处本就有兜底")
    void resetSequenceSwallowsFailure() {
        when(redis.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        pipeline.resetSequence(SESSION_ID);   // 不应抛出
    }
}
