package org.example.tracemind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TraceMind 自有配置，全部收在 {@code tracemind.*} 前缀下。
 *
 * <p>为什么不散用 {@code @Value}：一是改配置项时能在 IDE 里跳到一个地方看全貌；
 * 二是 {@code @Value} 拼错 key 只会得到一个 null，而 {@code @ConfigurationProperties}
 * 配合 spring-boot-configuration-processor 能给出元数据提示，不会静默失败。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "tracemind")
public class TraceMindProperties {

    private Python python = new Python();

    private Model model = new Model();

    private Fallback fallback = new Fallback();

    private WebSocket websocket = new WebSocket();

    /** Python 推理服务（trace-mind-ai）的连接参数 */
    @Data
    public static class Python {

        /** FastAPI 根地址，四个内部接口 /v1/* 都挂在这上面 */
        private String baseUrl = "http://127.0.0.1:8000";

        /** 单次探活超时（毫秒） */
        private int healthTimeoutMs = 2000;

        /** /v1/parse 单次调用超时（毫秒），日志导入批量调 Python 换模板 */
        private int parseTimeoutMs = 30000;
    }

    /** LLM 配置。Java 侧只负责透传给前端展示，真正调用在 Python 侧 */
    @Data
    public static class Model {

        /** 走 OpenAI 兼容协议，实际指向 Ollama */
        private String provider = "openai";

        private String model = "qwen-plus";

        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    /** ES 写入失败时的本地兜底配置 */
    @Data
    public static class Fallback {

        /** 兜底文件存放目录 */
        private String dir = "./fallback/es-retry";

        /** 单个兜底文件最大条数，超过就滚动生成新文件 */
        private int maxLinesPerFile = 10000;
    }

    /** WebSocket 推送端点配置 */
    @Data
    public static class WebSocket {

        /**
         * 允许的 Origin 前缀白名单（握手时匹配）。
         * 必须以前缀形式写全：http://localhost、https://your-domain.com。
         * 默认放行 localhost 与 127.0.0.1 的 HTTP/HTTPS。
         */
        private java.util.List<String> allowedOrigins = java.util.List.of(
                "http://127.0.0.1:", "http://localhost:",
                "https://127.0.0.1:", "https://localhost:");

        /**
         * 是否开启握手鉴权。
         * 开启后前端必须带 {@code ?ticket=xxx}，ticket 由 HTTP 接口签发并一次性消费。
         */
        private boolean enableAuth = false;

        /** 全局最大并发 WebSocket 连接数，超过后新连接返回 1008 */
        private int maxConnections = 1000;

        /** 服务端主动心跳间隔（秒），0 表示关闭，建议不超过 30 */
        private int heartbeatIntervalSec = 20;

        /** 回放历史时单条事件的发送超时（毫秒），防止 send 阻塞 Tomcat 线程 */
        private int replaySendTimeoutMs = 200;
    }
}