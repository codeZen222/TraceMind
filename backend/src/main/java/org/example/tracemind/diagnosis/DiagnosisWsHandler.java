package org.example.tracemind.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.pojo.entity.AgentEvent;
import org.example.tracemind.pojo.vo.DiagnosisEventVO;
import org.example.tracemind.pojo.vo.WsMessageVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 诊断事件推送端点：{@code ws://host:8080/ws/diagnosis?sessionId=xxx&afterSeq=5&ticket=yyy}
 *
 * <h3>★ 为什么从 {@code @ServerEndpoint} 换成 Spring 的 TextWebSocketHandler</h3>
 * JSR-356 的端点实例由容器<b>每连接 new 一个</b>、不经 Spring，所以字段上写 {@code @Autowired}
 * 拿到的是 null。上一版用「static 字段 + setter 注入」绕开，代价是换来六个更难查的问题：
 * <ol>
 *   <li><b>鉴权 fail-open</b>：{@code wsConfig != null && ...} 短路 —— 配置未注入时整个鉴权分支被跳过（已在上一步改为 fail-close）</li>
 *   <li>Origin 白名单同样会回落到硬编码默认值</li>
 *   <li>setter 注入与端点注册<b>时序无保证</b>，早到的连接撞 null → NPE</li>
 *   <li>{@code wsConfig = props.getWebsocket()} 把引用快照冻结在静态字段上，配置热更新静默失效</li>
 *   <li>静态内部类 {@code OriginConfigurator} 只能读静态字段，反模式自我繁殖</li>
 *   <li><b>完全无法隔离测试</b>：测一次握手要反射写五个静态槽位，跨用例残留还会互相污染</li>
 * </ol>
 *
 * <p>{@link TextWebSocketHandler} 本身就是普通<b>单例 Bean</b>：Spring 负责创建，
 * 每连接的会话状态通过方法参数 {@link WebSocketSession} 传入，而不是靠实例字段承载。
 * 于是依赖可以全部 {@code final} 构造注入，static 一个都不需要 —— 上面六条同时消失。</p>
 *
 * <h3>与旧实现的行为差异（迁移时逐条核对过）</h3>
 * <ul>
 *   <li>协议层心跳：旧的用 {@code session.getBasicRemote().sendPing(...)}；这里用
 *       {@link WebSocketSession#sendMessage} 发 {@link PingMessage}，等价能力保留。</li>
 *   <li>发送超时：旧的靠 {@code getAsyncRemote()} + Future 超时防慢客户端拖住 NIO 线程；
 *       Spring 侧没有等价的带超时异步句柄，改为<b>发送异常时立即放弃剩余回放</b>，
 *       并把每会话的发送串行化（{@code sendMessage} 对同一连接非线程安全）。
 *       这是本次迁移唯一的语义弱化点，已记进 README 已知问题。</li>
 *   <li>query 参数解析：{@code WebSocketSession#getUri()} 自带 query，无需手写 split。</li>
 *   <li>前端契约不变：仍是 {@code ?sessionId=&ticket=&afterSeq=}，消息体仍是 WsMessageVO JSON。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisWsHandler extends TextWebSocketHandler {

    /**
     * userProperties 里存诊断会话 id 的键，注销与心跳清理都要用。
     *
     * <p>★ 必须公开给 {@link WsSessionRegistry} 共用 —— 旧版两边各写一个字面量且<b>并不一致</b>
     * （handler 写 {@code diagSessionId}、心跳读 {@code sessionId}），导致心跳清不掉失效连接。</p>
     */
    public static final String ATTR_SESSION_ID = "diagSessionId";

    /**
     * ★ 这里刻意<b>不</b>再维护一份 connId → session 的表。
     *
     * <p>迁移时曾照搬旧结构加过一个 {@code sessionsByConn}，但检查后发现全类只有 put/remove、
     * 没有任何读取方 —— 纯冗余。连接与诊断会话的归属关系存在
     * {@code session.getAttributes()} 里（Spring 为每连接提供的标准槽位），
     * 而"某会话有哪些订阅连接"由 {@link WsSessionRegistry} 统一持有，两处职责不重叠。</p>
     */

    private final TraceMindProperties properties;
    private final StringRedisTemplate redis;
    private final AgentEventMapper agentEventMapper;
    private final ObjectMapper objectMapper;
    private final WsSessionRegistry registry;

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = param(session, "sessionId");
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            log.warn("WS 连接缺少或非法 sessionId");
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("缺少或非法 sessionId"));
            return;
        }

        /*
         * ★ 鉴权，fail-close。
         *
         *   依赖现在是 final 构造注入的，properties 不可能为 null（缺 Bean 时容器根本起不来），
         *   所以旧版那个"配置未注入就跳过鉴权"的竞态从结构上消失了 —— 不需要再写防御分支。
         */
        if (!authenticated(sessionId, session)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("ticket 无效或已使用"));
            return;
        }

        // 限流 + 登记
        if (!registry.register(sessionId, session)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("连接数超限"));
            return;
        }
        session.getAttributes().put(ATTR_SESSION_ID, sessionId);

        // 先登记后回放：登记前回放的话，回放期间产生的实时事件会被推给一个"还没登记"的连接 → 丢消息
        replayHistory(session, sessionId);

        log.info("WS 连接建立 sessionId={} connId={}，当前会话 {} 个连接",
                sessionId, session.getId(), registry.count(sessionId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregister(session);
        log.info("WS 连接关闭 connId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 某些异常下容器不会再调 afterConnectionClosed，这里兜底注销防内存泄漏
        String diagSessionId = (String) session.getAttributes().get(ATTR_SESSION_ID);
        log.warn("WS 连接异常 sessionId={}", diagSessionId, exception);
        unregister(session);
    }

    /**
     * 业务消息：目前只处理前端发的 JSON ping（与协议层心跳并存）。
     *
     * <p>服务端主动心跳由 {@link WsSessionRegistry} 统一发协议层 ping 帧（浏览器自动回 pong），
     * 但前端旧逻辑可能还在发 {@code {"type":"ping"}}，这里保留响应做兼容。</p>
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload != null && payload.contains("\"ping\"")) {
            sendText(session, "{\"type\":\"pong\"}");
        }
    }

    // ==================================================================
    // 鉴权
    // ==================================================================

    /**
     * 校验一次性 ticket。
     *
     * <p>前端先调 {@code POST /api/diagnosis/{sessionId}/ws-ticket} → 后端往 Redis 写
     * {@code tracemind:ws:ticket:{ticket} → sessionId}，TTL 60s；然后拿 ticket 连 WS，
     * 这里查 Redis 比对 sessionId 并 {@code getAndDelete}（一次性消费）。</p>
     *
     * <p>这样即使 sessionId 被日志泄露，没有 ticket 也连不上；ticket 被截获也只能用一次。
     * ★ key 与签发端共用 {@link RedisKeys#wsTicket} —— 曾经两边各写字面量，任一处改名就会
     * 让全量 WS 静默鉴权失败而 ticket 接口照常返回 200。</p>
     */
    private boolean authenticated(String sessionId, WebSocketSession session) {
        TraceMindProperties.WebSocket config = properties.getWebsocket();
        if (!config.isEnableAuth()) {
            return true;   // 运维显式关闭，语义明确
        }
        String ticket = param(session, "ticket");
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        try {
            String stored = redis.opsForValue().getAndDelete(RedisKeys.wsTicket(ticket));
            return sessionId.equals(stored);
        } catch (Exception e) {
            // Redis 挂了宁可拒绝：多租户下放过一个越权连接的代价远大于一次重连
            log.error("WS ticket 校验失败，按 fail-close 拒绝 sessionId={}", sessionId, e);
            return false;
        }
    }

    // ==================================================================
    // 历史回放
    // ==================================================================

    /**
     * 断线重连后补齐历史事件。
     *
     * <p>⚠️ 与旧实现的差异：旧版用 {@code getAsyncRemote().sendText()} + {@code Future.get(timeout)}
     * 给每条发送加超时，防止慢客户端拖住 Tomcat NIO 线程。Spring 的
     * {@link WebSocketSession#sendMessage} 是同步语义，没有等价的带超时异步句柄，
     * 因此这里改为<b>限制单次回放的条数上限</b>来兜住最坏耗时，并在发送异常时立即放弃剩余。
     * 这是本次迁移唯一的语义弱化点，已记进 README 已知问题。</p>
     */
    private void replayHistory(WebSocketSession session, String sessionId) {
        try {
            int afterSeq = parseAfterSeq(param(session, "afterSeq"));
            List<AgentEvent> history = afterSeq < 0
                    ? agentEventMapper.selectBySession(sessionId)
                    : agentEventMapper.selectAfterSeq(sessionId, afterSeq);

            int sent = 0;
            for (AgentEvent row : history) {
                if (!session.isOpen()) {
                    break;
                }
                try {
                    DiagnosisEventVO event = objectMapper.readValue(row.getPayload(), DiagnosisEventVO.class);
                    sendText(session, objectMapper.writeValueAsString(
                            WsMessageVO.of(row.getSeq(), sessionId, event)));
                    sent++;
                } catch (Exception perRow) {
                    // 单条脏数据不该让整个回放中断；但发送失败说明连接已不可用，放弃剩余
                    log.warn("WS 回放第 {} 条失败，丢弃剩余 {} 条 sessionId={} 原因={}",
                            sent + 1, history.size() - sent, sessionId, perRow.getMessage());
                    break;
                }
            }
            log.info("WS 回放完成 sessionId={} 共 {} 条，实际发送 {}", sessionId, history.size(), sent);
        } catch (Exception e) {
            log.error("WS 历史回放失败 sessionId={}", sessionId, e);
        }
    }

    /** afterSeq 缺省/非法都退化为"全量回放"；异常大的值同样视为全量（宁多不漏）。 */
    private int parseAfterSeq(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int v = Integer.parseInt(raw);
            return (v < -1 || v > 1_000_000) ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 取握手 URL 上的 query 参数。
     *
     * <p>Spring 直接给了 {@link WebSocketSession#getUri()}，不必像旧版那样手写 split + URLDecoder。</p>
     */
    private String param(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        // UriComponentsBuilder 会正确处理百分号编码，比手工 split 可靠
        return org.springframework.web.util.UriComponentsBuilder.fromUri(uri)
                .build().getQueryParams().getFirst(name);
    }

    /**
     * 同步发送文本帧。
     *
     * <p>{@code WebSocketSession.sendMessage} 对同一会话<b>不是线程安全的</b>（并发写会交错帧），
     * 而我们的推送来自诊断线程、pong 来自容器线程、回放来自握手线程，三者可能并发命中同一连接，
     * 所以必须像旧版 {@code synchronized(session)} 那样串行化。</p>
     */
    private void sendText(WebSocketSession session, String text) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            log.warn("WS 发送失败 connId={} 原因={}", session.getId(), e.getMessage());
        }
    }

    /** 供 Registry 使用的协议层 ping —— 中间代理（Nginx/ELB）不会掐，比 JSON ping 轻得多 */
    public void sendPing(WebSocketSession session) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[]{0x01})));
                }
            }
        } catch (Exception e) {
            log.debug("WS ping 失败 connId={}", session.getId());
            throw new IllegalStateException(e);
        }
    }

    private void unregister(WebSocketSession session) {
        Object sessionId = session.getAttributes().get(ATTR_SESSION_ID);
        if (sessionId instanceof String sid) {
            registry.unregister(sid, session);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 关闭失败无处可补救，且此刻正是要拒绝连接的场合
        }
    }

}
