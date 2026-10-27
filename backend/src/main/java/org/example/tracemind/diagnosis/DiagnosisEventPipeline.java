package org.example.tracemind.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.pojo.entity.AgentEvent;
import org.example.tracemind.pojo.vo.DiagnosisEventVO;
import org.example.tracemind.pojo.vo.WsMessageVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 诊断事件流水线：<b>发号 → 落库 → 推送</b>。
 *
 * <h3>为什么单独成类</h3>
 * 这三步是一条有铁律顺序的独立职责，此前住在 {@code DiagnosisServiceImpl.handleEvent} 里——
 * 而那个方法<b>故意没有出现在 {@code DiagnosisService} 接口上</b>，接口注释写着
 * 「它是实现内部的方法」。那已经是承认它属于另一个对象，只是还没搬出去。
 *
 * <p>搬出来带来的直接收益是可测性：原先要测这条流水线得凑齐 DiagnosisServiceImpl 的
 * 11 个协作者；现在只需要 Redis、Mapper、Registry 三个。</p>
 *
 * <h3>★ 铁律：顺序不能反</h3>
 * <ol>
 *   <li>{@code seq = INCR tracemind:diag:seq:{sessionId}} —— 单调递增，会话内严格自增</li>
 *   <li><b>先落 {@code agent_event}</b></li>
 *   <li><b>再推 WebSocket</b></li>
 * </ol>
 * 先推后落的话，用户在事件到达的瞬间刷新页面做历史回放会缺这一条 —— 回放读的是库，
 * 而那时它还没落进去。断线重连靠 {@code afterSeq} 补齐，同样依赖库里已有数据。
 *
 * <h3>三类失败，处置各不相同（都是刻意的）</h3>
 * <table border="1">
 *   <tr><th>失败点</th><th>处置</th><th>为什么</th></tr>
 *   <tr><td>seq 发号失败（Redis 挂）</td><td>整条事件丢弃，不落不推</td>
 *       <td>顺序性是回放正确性的地基。伪造一个 seq 会让回放乱序，比少一条事件更糟。</td></tr>
 *   <tr><td>落库失败</td><td>记 error，<b>仍然继续推送</b></td>
 *       <td>用户至少能看到实时过程。代价是这条不出现在历史回放里 —— 让它可见比让它完美重要。</td></tr>
 *   <tr><td>推送失败／无人监听</td><td>不影响落库</td>
 *       <td>Registry 自己判空跳过；落库已完成，重连时能补上。</td></tr>
 * </table>
 *
 * <p>⚠️ 本类<b>不</b>更新会话统计（llmUsed / matchedAnalyzer / total_cost_ms 那些）。
 * 那是领域状态机、规则里有业务不变量（例如「命中过规则的会话不可能走过 LLM」），
 * 留在 {@code DiagnosisServiceImpl.applyToSession}，由调用方在流水线之后执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisEventPipeline {

    private final StringRedisTemplate redis;
    private final AgentEventMapper agentEventMapper;
    /**
     * 推送端口。这里刻意依赖接口而不是 {@code WsSessionRegistry} 具体类 ——
     * 后者持有 {@code jakarta.websocket.Session}，把业务和 JSR-356 焊在一起会挡住传输层演进。
     */
    private final DiagnosisEventPublisher publisher;
    private final ObjectMapper objectMapper;

    /**
     * 处理一个诊断事件：发号、落库、推送。
     *
     * @return 实际使用的 seq；<b>返回 null 表示该事件被丢弃</b>（发号失败），
     *         调用方据此决定要不要继续做后续处理（例如会话统计是否要跟着变）
     */
    public Integer publish(String sessionId, DiagnosisEventVO event) {
        // 1) 发号
        Long seq;
        try {
            seq = redis.opsForValue().increment(RedisKeys.diagnosisSeq(sessionId));
        } catch (Exception e) {
            log.error("Redis 不可用，无法生成 seq，该事件不落库也不推送 sessionId={} type={}",
                    sessionId, event.getType(), e);
            return null;
        }
        if (seq == null) {
            log.error("seq 发号返回 null，跳过 sessionId={}", sessionId);
            return null;
        }

        // 2) 先落库
        try {
            AgentEvent row = new AgentEvent();
            row.setSessionId(sessionId);
            row.setSeq(seq.intValue());
            row.setEventType(event.getType());
            row.setPayload(objectMapper.writeValueAsString(event));
            agentEventMapper.insert(row);
        } catch (Exception e) {
            // 落库失败仍然继续推送：让它可见比让它完美重要（详见类注释的失败表）
            log.error("事件落库失败 sessionId={} seq={} type={}", sessionId, seq, event.getType(), e);
        }

        // 3) 后推送（没人听就自动跳过，不影响落库 —— 见 DiagnosisEventPublisher#send 的语义约束）
        publisher.send(sessionId, WsMessageVO.of(seq.intValue(), sessionId, event));

        return seq.intValue();
    }

    /**
     * 重置会话的发号器。新会话触发时调用，防的是上次同名会话留下的脏 key。
     *
     * <p>这是个优化项、不是前置条件：失败也只记日志，因为 {@link #publish} 自己有兜底。</p>
     */
    public void resetSequence(String sessionId) {
        try {
            redis.delete(RedisKeys.diagnosisSeq(sessionId));
        } catch (Exception e) {
            log.warn("清理 seq 计数器失败，继续触发诊断。sessionId={} 原因={}", sessionId, e.getMessage());
        }
    }
}
