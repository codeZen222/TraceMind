package org.example.tracemind.diagnosis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手前的 Origin 白名单校验。
 *
 * <h3>为什么单独成类</h3>
 * JSR-356 时代它只能写成 handler 的<b>静态内部类</b>（{@code ServerEndpointConfig.Configurator}
 * 由容器 new、拿不到注入），于是只能读那个 static 的 {@code wsConfig} 字段 —— 反模式自我繁殖的典型。
 *
 * <p>Spring 侧有标准位置：{@link HandshakeInterceptor}。它本身是 Bean，构造注入即可，
 * 所以这个类不需要任何 static。</p>
 *
 * <h3>★ fail-close</h3>
 * 旧实现在配置为 null 时会回落到一份硬编码 localhost 白名单并放行。生产环境本来只该允许自己的域名，
 * "临时放行"没人看得见也没日志。这里改成拒绝。
 *
 * <p>不过迁移后依赖是 final 构造注入的，{@code properties} 不可能为 null（缺 Bean 时容器起不来），
 * 所以这条防御实际上已经变成结构上的保证 —— 保留判断只是为了让意图显式。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private final TraceMindProperties properties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HttpHeaders headers = request.getHeaders();
        String origin = headers.getOrigin();

        if (origin == null || origin.isBlank()) {
            // 无 Origin 头：非浏览器客户端（curl / 探针）。放行 —— 跨站攻击面只存在于浏览器。
            return true;
        }

        List<String> allowed = properties.getWebsocket().getAllowedOrigins();
        if (allowed == null || allowed.isEmpty()) {
            log.error("WS Origin 白名单为空配置，按 fail-close 拒绝握手 Origin={}", origin);
            return false;
        }

        boolean ok = allowed.stream().anyMatch(origin::startsWith);
        if (!ok) {
            log.warn("拒绝 WS 握手，Origin 不在白名单: {}", origin);
        }
        return ok;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.warn("WS 握手完成但带异常", exception);
        }
    }
}
