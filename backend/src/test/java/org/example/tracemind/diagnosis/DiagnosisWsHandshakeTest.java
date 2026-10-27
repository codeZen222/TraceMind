package org.example.tracemind.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.config.TraceMindProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS 握手准入语义 —— Spring WebSocket 迁移版。
 *
 * <p>与 JSR-356 时代最大的区别：<b>不再需要反射</b>。旧实现把依赖塞在 static 字段里，
 * 测一次握手要反射写五个静态槽位、还要担心跨用例残留污染（重构评估 R-4.1 记的第 ⑥ 条）。
 * 现在 {@link DiagnosisWsHandler} 是普通单例 Bean、依赖 final 构造注入，
 * 直接 new 出来就能测。<b>这就是那次迁移最实在的收益。</b></p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosisWsHandshakeTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private WsSessionRegistry registry;

    private TraceMindProperties properties;
    private DiagnosisWsHandler handler;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new TraceMindProperties();
        handler = new DiagnosisWsHandler(properties, redis,
                mock(org.example.tracemind.mapper.AgentEventMapper.class),
                new ObjectMapper(), registry);
    }

    /** 造一个带指定 query 的会话；Origin 单独走 handshakeHeaders */
    private WebSocketSession sessionWithQuery(String query, String origin) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getUri()).thenReturn(URI.create("ws://127.0.0.1:8080/ws/diagnosis"
                + (query == null ? "" : "?" + query)));
        when(s.getId()).thenReturn("conn-1");
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());
        when(s.isOpen()).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        when(s.getHandshakeHeaders()).thenReturn(headers);
        return s;
    }

    // ==================================================================
    // Origin 白名单（已挪到 HandshakeInterceptor）
    // ==================================================================

    @Nested
    @DisplayName("WsHandshakeInterceptor：Origin 校验")
    class OriginWhitelist {

        /**
         * 用 mock 造握手请求，只关心 Origin 头。
         *
         * <p>不用 {@code MockServerHttpRequest}：它属于 reactive 测试支持、包路径在不同 Spring 版本间
         * 挪过位置（6.2 在 {@code org.springframework.mock.http.server.reactive}），
         * 为一个断言引这类依赖不值得。mock 反而更直白。</p>
         */
        private boolean accept(String origin) {
            WsHandshakeInterceptor interceptor = new WsHandshakeInterceptor(properties);

            HttpHeaders headers = new HttpHeaders();
            if (origin != null) {
                headers.set(HttpHeaders.ORIGIN, origin);
            }
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);

            return interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), handler,
                    new java.util.HashMap<>());
        }

        @Test
        @DisplayName("★ 配置未注入时不可能发生：properties 是 final 构造注入，缺 Bean 容器起不来")
        void configCannotBeNullAnymore() {
            // 这条断言取代了旧版"static 为 null 则拒绝"的用例 —— 那个竞态已从结构上消失，
            // 不再是运行期需要防御的分支。留个测试记录这个事实，防止有人退回 static 写法。
            assertThat(new DiagnosisWsHandler(properties, redis,
                    mock(org.example.tracemind.mapper.AgentEventMapper.class),
                    new ObjectMapper(), registry)).isNotNull();
        }

        @Test
        @DisplayName("无 Origin 头（非浏览器客户端）→ 放行")
        void allowsRequestWithoutOriginHeader() {
            assertThat(accept(null)).isTrue();
        }

        @Test
        @DisplayName("按配置的白名单前缀匹配")
        void matchesConfiguredPrefixes() {
            properties.getWebsocket().setAllowedOrigins(java.util.List.of("http://localhost:"));

            assertThat(accept("http://localhost:5173")).isTrue();
            assertThat(accept("https://localhost:5173")).isFalse();   // 协议不同，前缀不匹配
            assertThat(accept("http://evil.example.com")).isFalse();
        }

        @Test
        @DisplayName("★ 白名单为空 → 拒绝（fail-close），不放行任何浏览器 Origin")
        void rejectsOnEmptyWhitelist() {
            properties.getWebsocket().setAllowedOrigins(java.util.List.of());

            assertThat(accept("http://localhost:5173")).isFalse();
        }

        @Test
        @DisplayName("默认配置放行本机前后端联调端口")
        void defaultAllowsLocalDevOrigins() {
            assertThat(accept("http://localhost:5173")).isTrue();
            assertThat(accept("http://127.0.0.1:5173")).isTrue();
        }
    }

    // ==================================================================
    // ticket 鉴权
    // ==================================================================

    @Nested
    @DisplayName("ticket 鉴权：一次性票据 + 会话绑定")
    class TicketAuth {

        @Test
        @DisplayName("enable-auth=false → 不必带票")
        void allowsWhenAuthDisabled() throws Exception {
            properties.getWebsocket().setEnableAuth(false);
            WebSocketSession s = sessionWithQuery("sessionId=sess-001", null);
            when(registry.register(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s, org.mockito.Mockito.never()).close(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("★ enable-auth=true 却没带 ticket → 关闭连接")
        void rejectsWhenNoTicket() throws Exception {
            properties.getWebsocket().setEnableAuth(true);
            WebSocketSession s = sessionWithQuery("sessionId=sess-001", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s).close(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                    .register(anyString(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("★ ticket 查不到（无效或已被消费）→ 关闭连接")
        void rejectsWhenTicketUnknownOrUsed() throws Exception {
            properties.getWebsocket().setEnableAuth(true);
            when(valueOps.getAndDelete(anyString())).thenReturn(null);
            WebSocketSession s = sessionWithQuery("sessionId=sess-001&ticket=abc", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s).close(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("★ ticket 属于别的会话 → 拒绝（多租户越权的核心防线）")
        void rejectsWhenTicketBelongsToAnotherSession() throws Exception {
            properties.getWebsocket().setEnableAuth(true);
            when(valueOps.getAndDelete(anyString())).thenReturn("sess-VICTIM");
            WebSocketSession s = sessionWithQuery("sessionId=sess-ATTACKER&ticket=stolen", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s).close(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
                    .register(anyString(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("ticket 与会话匹配 → 登记并回放")
        void acceptsWhenTicketMatches() {
            properties.getWebsocket().setEnableAuth(true);
            when(valueOps.getAndDelete(anyString())).thenReturn("sess-001");
            when(registry.register(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
            WebSocketSession s = sessionWithQuery("sessionId=sess-001&ticket=good", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(registry).register(org.mockito.ArgumentMatchers.eq("sess-001"),
                    org.mockito.ArgumentMatchers.same(s));
        }

        @Test
        @DisplayName("★ Redis 挂了 → 拒绝而非放行（fail-close）")
        void rejectsWhenRedisDown() throws Exception {
            properties.getWebsocket().setEnableAuth(true);
            when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
            WebSocketSession s = sessionWithQuery("sessionId=sess-001&ticket=abc", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s).close(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("非法 sessionId（缺失 / 超长）→ 拒绝，不去碰 Redis 和库")
        void rejectsMalformedSessionId() throws Exception {
            properties.getWebsocket().setEnableAuth(false);

            WebSocketSession missing = sessionWithQuery("ticket=abc", null);
            WebSocketSession tooLong = sessionWithQuery("sessionId=" + "x".repeat(200), null);

            handler.afterConnectionEstablished(missing);
            handler.afterConnectionEstablished(tooLong);

            org.mockito.Mockito.verify(missing).close(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(tooLong).close(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("连接数超限 → 拒绝")
        void rejectsWhenOverLimit() throws Exception {
            properties.getWebsocket().setEnableAuth(false);
            when(registry.register(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
            WebSocketSession s = sessionWithQuery("sessionId=sess-001", null);

            handler.afterConnectionEstablished(s);

            org.mockito.Mockito.verify(s).close(org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @DisplayName("★ 心跳与 handler 共用同一个 attributes 键（旧版两边不一致导致清理失效）")
    void sharesOneAttributeKey() {
        assertThat(DiagnosisWsHandler.ATTR_SESSION_ID).isEqualTo("diagSessionId");
    }
}
