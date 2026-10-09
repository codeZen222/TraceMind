package org.example.tracemind.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.vo.WsMessageVO;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 连接登记表：sessionId → 连接集合。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>{@link CopyOnWriteArraySet} 遍历远多于增删，遍历无锁且不怕并发修改</li>
 *   <li>单独一个类：{@code @ServerEndpoint} 每连接一个实例，存不了全局状态</li>
 *   <li>全局连接计数器用于限流，防止恶意刷连接耗光服务器 FD</li>
 *   <li>单线程 {@link ScheduledExecutorService} 统一发协议层 ping 心跳 ——
 *       不在每个 Session 上起定时任务，避免连接数 × 线程数的爆炸</li>
 *   <li>监听 {@link ContextClosedEvent}，应用关闭时主动关闭所有 Session，让前端感知到正常断开</li>
 * </ul>
 */
@Component
@Slf4j
public class WsSessionRegistry implements ApplicationListener<ContextClosedEvent> {

    private final Map<String, Set<Session>> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ObjectMapper objectMapper;
    private final TraceMindProperties.WebSocket wsConfig;
    private final ScheduledExecutorService heartbeatExecutor;

    public WsSessionRegistry(ObjectMapper objectMapper, TraceMindProperties traceMindProperties) {
        this.objectMapper = objectMapper;
        this.wsConfig = traceMindProperties.getWebsocket();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        int interval = wsConfig.getHeartbeatIntervalSec();
        if (interval > 0) {
            this.heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats,
                    interval, interval, TimeUnit.SECONDS);
        }
    }

    /**
     * 登记连接。超过 maxConnections 时返回 false，由调用方关闭 Session。
     */
    public boolean register(String sessionId, Session session) {
        int max = wsConfig.getMaxConnections();
        if (totalConnections.incrementAndGet() > max) {
            totalConnections.decrementAndGet();
            log.warn("WS 连接数超过上限 {}，拒绝新连接 sessionId={}", max, sessionId);
            return false;
        }
        sessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.debug("WS 已登记 sessionId={}，该会话 {} 个连接，全局 {}",
                sessionId, sessions.get(sessionId).size(), totalConnections.get());
        return true;
    }

    public void unregister(String sessionId, Session session) {
        Set<Session> set = sessions.get(sessionId);
        if (set == null) {
            return;
        }
        set.remove(session);
        totalConnections.decrementAndGet();
        if (set.isEmpty()) {
            sessions.remove(sessionId);
        }
        log.debug("WS 已注销 sessionId={}，全局剩余 {}", sessionId, totalConnections.get());
    }

    /**
     * 推一条消息给该会话的所有连接。
     *
     * <p><b>没有连接时静默跳过</b> —— 诊断事件先落库，推送失败不影响数据完整性。</p>
     *
     * <p><b>不要在遍历 CopyOnWriteArraySet 时直接调用 set.remove()</b>——
     * CopyOnWriteArraySet 的 iterator 是快照，遍历期间对 set 的修改不会反映到 iterator。
     * 用外部 {@code dead} 列表收集，遍历完再统一 remove。</p>
     */
    public void send(String sessionId, WsMessageVO message) {
        Set<Session> set = sessions.get(sessionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("WS 消息序列化失败 sessionId={}", sessionId, e);
            return;
        }

        List<Session> dead = new ArrayList<>();
        for (Session session : set) {
            if (!session.isOpen()) {
                dead.add(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.getBasicRemote().sendText(json);
                }
            } catch (Exception e) {
                log.warn("WS 推送失败，标记移除 sessionId={}", sessionId, e);
                dead.add(session);
            }
        }
        cleanupDead(sessionId, dead);
    }

    /** 服务端定时发协议层 ping 帧 —— 中间代理（Nginx/ELB）不会掐，比 JSON ping 轻得多 */
    private void sendHeartbeats() {
        List<Session> dead = new ArrayList<>();
        for (Set<Session> set : sessions.values()) {
            for (Session session : set) {
                if (!session.isOpen()) {
                    dead.add(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.getBasicRemote().sendPing(ByteBuffer.wrap(new byte[]{0x01}));
                    }
                } catch (Exception e) {
                    dead.add(session);
                }
            }
        }
        if (!dead.isEmpty()) {
            log.info("WS 心跳清理 {} 个失效连接", dead.size());
            for (Session s : dead) {
                Set<Session> set = sessions.get(s.getUserProperties().get("sessionId"));
                if (set != null) {
                    set.remove(s);
                    totalConnections.decrementAndGet();
                }
            }
            sessions.entrySet().removeIf(e -> {
                boolean empty = e.getValue().isEmpty();
                if (empty) log.debug("WS 移除空 sessionId={}", e.getKey());
                return empty;
            });
        }
    }

    private void cleanupDead(String sessionId, List<Session> dead) {
        if (dead.isEmpty()) return;
        Set<Session> set = sessions.get(sessionId);
        if (set == null) return;
        for (Session s : dead) {
            set.remove(s);
            totalConnections.decrementAndGet();
        }
        if (set.isEmpty()) {
            sessions.remove(sessionId);
        }
    }

    /** 当前监听某个会话的连接数 */
    public int count(String sessionId) {
        Set<Session> set = sessions.get(sessionId);
        return set == null ? 0 : set.size();
    }

    /** 全局 WebSocket 连接数（用于监控） */
    public int totalConnections() {
        return totalConnections.get();
    }

    /** 优雅关闭：Spring 容器关闭时主动踢掉所有连接，让前端感知到是服务端主动下线 */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("应用关闭，主动清理所有 WS 连接，当前共 {}", totalConnections.get());
        heartbeatExecutor.shutdownNow();
        Iterator<Map.Entry<String, Set<Session>>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Set<Session>> e = it.next();
            for (Session session : e.getValue()) {
                try {
                    if (session.isOpen()) {
                        session.close(new jakarta.websocket.CloseReason(
                                jakarta.websocket.CloseReason.CloseCodes.GOING_AWAY, "server shutdown"));
                    }
                } catch (Exception ignored) {
                }
            }
            it.remove();
        }
        totalConnections.set(0);
    }
}