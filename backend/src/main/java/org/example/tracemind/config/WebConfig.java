package org.example.tracemind.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：CORS。
 *
 * <p>前端 Vue3 dev server 跑在 5173，后端在 8080，端口不同就是跨域。
 * 用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins("*")}——后者一旦和
 * {@code allowCredentials(true)} 同时出现，Spring 会直接抛
 * "When allowCredentials is true, allowedOrigins cannot contain '*'"，
 * 而且这个限制在启动时才炸，排查很浪费时间。</p>
 *
 * <p>只放行本机回环地址，不要图省事写 {@code *}：这个服务能读写文件系统、能发起外网请求，
 * 浏览器端放开任意来源等于把本地能力暴露给任何网页。</p>
 *
 * <p>WebSocket 的 {@code /ws/**} 握手校验不在这里——原生 WS 握手走的不是 CORS 过滤器，
 * 要在 {@code HandshakeInterceptor} 里自己判 Origin，Step-J6 补。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String[] LOCAL_ORIGIN_PATTERNS = {
            "http://localhost:[*]",
            "http://127.0.0.1:[*]"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(LOCAL_ORIGIN_PATTERNS)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
