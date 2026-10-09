package org.example.tracemind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * 让 {@code @ServerEndpoint} 生效。
 *
 * <p>Spring Boot 的内嵌容器（Tomcat/Jetty/Undertow）<b>不会自动扫描</b> {@code @ServerEndpoint}，
 * 必须手动注册这个 {@link ServerEndpointExporter} bean。在项目里搜不到"为什么端点不生效"的答案时，
 * 八成是漏了它。</p>
 *
 * <p>⚠️ <b>打包成 war 部署到外部 Tomcat 时要删掉这个 Bean</b>——
 * 那时容器自己负责扫描端点，重复注册会报错。</p>
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}