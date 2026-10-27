package org.example.tracemind.config;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.diagnosis.DiagnosisWsHandler;
import org.example.tracemind.diagnosis.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册。
 *
 * <h3>★ 为什么不再需要 {@code ServerEndpointExporter}</h3>
 * 旧版用 JSR-356 的 {@code @ServerEndpoint}，而 Spring Boot 的内嵌容器<b>不会自动扫描</b>它，
 * 必须手动注册一个 {@code ServerEndpointExporter} bean 才能让端点生效 —— 于是产生了两个副作用：
 * <ol>
 *   <li>端点实例由容器每连接 new、不经 Spring，依赖只能靠 static 字段绕（见
 *       {@link DiagnosisWsHandler} 类注释里那六条风险）；</li>
 *   <li>{@code contextLoads} 冒烟测试恒失败：嵌入式测试容器没有 JSR-356 的
 *       {@code ServerContainer}，exporter 初始化就抛
 *       {@code IllegalStateException: jakarta.websocket.server.ServerContainer not available}。</li>
 * </ol>
 *
 * <p>改用 Spring 原生的 {@link EnableWebSocket} + {@link WebSocketConfigurer} 后：handler 是普通单例 Bean、
 * exporter 不再需要（那个冒烟测试的失败根因也随之消失），Origin 校验挪到
 * {@link WsHandshakeInterceptor} 这个标准位置。</p>
 *
 * <p>⚠️ 前端契约未变：路径仍是 {@code /ws/diagnosis}，query 参数仍是
 * {@code sessionId / ticket / afterSeq}，消息体仍是 WsMessageVO 的 JSON。</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DiagnosisWsHandler diagnosisWsHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(diagnosisWsHandler, "/ws/diagnosis")
                .addInterceptors(handshakeInterceptor);
        // ⚠️ 刻意不调 setAllowedOrigins()：Spring 默认的同源检查会挡掉开发期 Vite(5173) → 后端(8080)
        //   这种跨端口访问，而项目已有自己的 Origin 白名单逻辑（在 interceptor 里，走配置）。
        //   把准入判断收在一处，避免两套规则互相覆盖导致"看着放行其实被拦"。
    }
}
