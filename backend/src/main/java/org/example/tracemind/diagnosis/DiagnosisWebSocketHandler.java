package org.example.tracemind.diagnosis;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.pojo.entity.AgentEvent;
import org.example.tracemind.pojo.vo.DiagnosisEventVO;
import org.example.tracemind.pojo.vo.WsMessageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 诊断事件推送端点：{@code ws://host:8080/ws/diagnosis?sessionId=xxx&afterSeq=5&ticket=yyy}
 *
 * <p>生产级增强：</p>
 * <ul>
 *   <li>Origin 白名单走配置，不再硬编码 localhost</li>
 *   <li>可选握手鉴权：ticket 由 HTTP 接口签发、Redis 存、一次性消费、比对 sessionId</li>
 *   <li>全局连接数限流，防恶意刷 FD</li>
 *   <li>回放历史时单条 send 有超时保护，防止慢客户端拖住 Tomcat NIO 线程</li>
 *   <li>onError 里也注销 Session，避免内存泄漏</li>
 *   <li>服务端主动心跳由 {@link WsSessionRegistry} 统一发协议层 ping 帧，单线程搞定</li>
 * </ul>
 *
 * <p><b>最容易踩的一条</b>：{@code @ServerEndpoint} 的实例是<b>每个连接一个</b>，
 * 由容器 new 出来，不是 Spring 单例。所以字段上写 {@code @Autowired} 拿到的是 null。
 * 标准解法是 <b>static 字段 + setter 注入</b>（下面就是这样）。</p>
 */
@Component
@ServerEndpoint(value = "/ws/diagnosis", configurator = DiagnosisWebSocketHandler.OriginConfigurator.class)
@Slf4j
public class DiagnosisWebSocketHandler {

    // ★ static —— 见类注释。非 static 的话每个连接一个实例、每个实例都是 null
    private static WsSessionRegistry registry;
    private static AgentEventMapper agentEventMapper;
    private static ObjectMapper objectMapper;
    private static TraceMindProperties.WebSocket wsConfig;
    private static StringRedisTemplate redis;

    @Autowired
    public void setRegistry(WsSessionRegistry registry) {
        DiagnosisWebSocketHandler.registry = registry;
    }

    @Autowired
    public void setAgentEventMapper(AgentEventMapper agentEventMapper) {
        DiagnosisWebSocketHandler.agentEventMapper = agentEventMapper;
    }

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        DiagnosisWebSocketHandler.objectMapper = objectMapper;
    }

    @Autowired
    public void setTraceMindProperties(TraceMindProperties traceMindProperties) {
        DiagnosisWebSocketHandler.wsConfig = traceMindProperties.getWebsocket();
    }

    @Autowired
    public void setRedis(StringRedisTemplate redis) {
        DiagnosisWebSocketHandler.redis = redis;
    }

    // ==================================================================
    // Origin 校验：读配置
    // ==================================================================

    /**
     * 握手前放行 Origin。
     *
     * <p>WebSocket <b>不走 CORS</b>，握手时由服务端自己校验。
     * 白名单从 {@code tracemind.websocket.allowed-origins} 读取，生产环境改配置即可。</p>
     *
     * <p>⚠️ {@code Configurator} 是静态内部类，没法直接拿 Spring 注入，
     * 只能从 {@link DiagnosisWebSocketHandler#wsConfig} 这个 static 字段读。</p>
     */
    public static class OriginConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public boolean checkOrigin(String originHeaderValue) {
            if (originHeaderValue == null || originHeaderValue.isBlank()) {
                return true;
            }
            List<String> allowed = wsConfig != null
                    ? wsConfig.getAllowedOrigins()
                    : List.of("http://127.0.0.1:", "http://localhost:",
                              "https://127.0.0.1:", "https://localhost:");
            boolean ok = allowed.stream().anyMatch(originHeaderValue::startsWith);
            if (!ok) {
                log.warn("拒绝 WS 握手，Origin 不在白名单: {}", originHeaderValue);
            }
            return ok;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 连接建立。
     *
     * <p>顺序：鉴权 → 限流 → 登记 → 回放历史。</p>
     *
     * <p>先登记后回放 vs 先回放后登记 —— 当前代码是<b>先登记后回放</b>，
     * 理由：登记意味着"从现在起我愿意接收实时推送"，登记后才能让 Registry 知道有新连接了，
     * 如果登记前就回放，回放期间产生的实时事件会被推给一个"还没登记"的连接 → 丢消息。</p>
     */
    @OnOpen
    public void onOpen(Session session) {
        String sessionId = queryParam(session, "sessionId");
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            log.warn("WS 连接缺少或非法 sessionId");
            close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "缺少或非法 sessionId");
            return;
        }

        // 鉴权（开关可控）
        if (wsConfig != null && wsConfig.isEnableAuth() && !verifyTicket(sessionId, session)) {
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "ticket 无效或已使用");
            return;
        }

        // 限流 + 登记
        if (!registry.register(sessionId, session)) {
            close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, "连接数超限");
            return;
        }
        session.getUserProperties().put("sessionId", sessionId);

        // 回放历史（登记后回放，避免回放期间产生的实时事件丢失）
        replayHistory(session, sessionId);

        log.info("WS 连接建立 sessionId={}，当前会话 {} 个连接", sessionId, registry.count(sessionId));
    }

    @OnClose
    public void onClose(Session session) {
        String sessionId = (String) session.getUserProperties().get("sessionId");
        if (sessionId != null) {
            registry.unregister(sessionId, session);
        }
        log.info("WS 连接关闭 sessionId={}", sessionId);
    }

    /**
     * onError 必须也注销 —— 某些异常容器不会再调用 onClose，兜底防止内存泄漏。
     */
    @OnError
    public void onError(Session session, Throwable error) {
        String sessionId = (String) session.getUserProperties().get("sessionId");
        log.warn("WS 连接异常 sessionId={}", sessionId, error);
        if (sessionId != null) {
            registry.unregister(sessionId, session);
        }
    }

    /**
     * 业务消息：目前只处理前端发的 JSON ping（与心跳机制并存）。
     *
     * <p>服务端主动心跳由 {@link WsSessionRegistry} 统一发协议层 ping 帧（浏览器自动回 pong 帧），
     * 但前端旧逻辑可能还在发 {@code {"type":"ping"}}，这里保留响应做兼容。</p>
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        if (message != null && message.contains("\"ping\"")) {
            sendText(session, "{\"type\":\"pong\"}");
        }
    }

    // ==================================================================
    // 鉴权
    // ==================================================================

    /**
     * 校验 ticket。ticket 由 HTTP 接口提前签发：
     * 前端先调 {@code POST /api/ws/ticket} → 后端往 Redis 写一条
     * {@code ws:ticket:{ticket} → sessionId}，TTL 60s。
     * 然后前端拿 ticket 连 WS，这里查 Redis 比对 sessionId，查到就删（一次性消费）。
     *
     * <p>这样即使 sessionId 被日志泄露，没有 ticket 也连不上；ticket 又是一次性的，
     * 被截获也只能用一次。</p>
     */
    private boolean verifyTicket(String sessionId, Session session) {
        String ticket = queryParam(session, "ticket");
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        try {
            String redisKey = "ws:ticket:" + ticket;
            String stored = redis.opsForValue().getAndDelete(redisKey);
            return sessionId.equals(stored);
        } catch (Exception e) {
            log.error("WS ticket 校验失败 sessionId={}", sessionId, e);
            return false;
        }
    }

    // ==================================================================
    // 历史回放
    // ==================================================================

    /**
     * 回放历史。send 用 AsyncRemote 加超时保护 —— 慢客户端拖住 Tomcat 线程是 P0 级风险。
     * 超时就放弃剩余的，前端靠重连 + afterSeq 自己补。
     */
    private void replayHistory(Session session, String sessionId) {
        try {
            String afterSeqRaw = queryParam(session, "afterSeq");
            int afterSeq;
            try {
                afterSeq = (afterSeqRaw == null || afterSeqRaw.isBlank()) ? -1 : Integer.parseInt(afterSeqRaw);
            } catch (NumberFormatException e) {
                afterSeq = -1;
            }
            if (afterSeq < -1) afterSeq = -1;
            if (afterSeq > 1_000_000) afterSeq = -1;   // 不可能的高 seq，全量回

            List<AgentEvent> history = afterSeq < 0
                    ? agentEventMapper.selectBySession(sessionId)
                    : agentEventMapper.selectAfterSeq(sessionId, afterSeq);

            int timeoutMs = wsConfig != null ? wsConfig.getReplaySendTimeoutMs() : 200;
            int sent = 0;
            for (AgentEvent row : history) {
                if (!session.isOpen()) break;
                DiagnosisEventVO event = objectMapper.readValue(row.getPayload(), DiagnosisEventVO.class);
                String text = objectMapper.writeValueAsString(
                        WsMessageVO.of(row.getSeq(), sessionId, event));
                if (sendTextWithTimeout(session, text, timeoutMs)) {
                    sent++;
                } else {
                    log.warn("WS 回放 send 超时，丢弃剩余 {} 条 sessionId={}",
                            history.size() - sent, sessionId);
                    break;
                }
            }
            log.info("WS 回放完成 sessionId={} 共 {} 条，实际发送 {}", sessionId, history.size(), sent);
        } catch (Exception e) {
            log.error("WS 历史回放失败 sessionId={}", sessionId, e);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String queryParam(Session session, String name) {
        String qs = session.getQueryString();
        if (qs == null || qs.isBlank()) return null;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 异步发送 + 超时。{@link Session#getAsyncRemote()} 不阻塞 Tomcat NIO 线程，
     * Future.get() 等结果只等 timeoutMs ，超时就 cancel 继续下一条。
     */
    private boolean sendTextWithTimeout(Session session, String text, int timeoutMs) {
        Future<?> f = session.getAsyncRemote().sendText(text);
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            return false;
        } catch (Exception e) {
            log.debug("WS 回放 send 异常", e);
            return false;
        }
    }

    private void sendText(Session session, String text) {
        try {
            synchronized (session) {
                session.getBasicRemote().sendText(text);
            }
        } catch (Exception e) {
            log.warn("WS 发送失败", e);
        }
    }

    private void close(Session session, CloseReason.CloseCode code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (Exception ignored) {
        }
    }
}