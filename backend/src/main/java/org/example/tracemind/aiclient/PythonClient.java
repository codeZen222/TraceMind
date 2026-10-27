package org.example.tracemind.aiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.Utilx;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.vo.ChatMessageVO;
import org.example.tracemind.pojo.vo.DiagnosisEventVO;
import org.example.tracemind.pojo.vo.LogEntryVO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Python 推理服务（trace-mind-ai）的唯一出口。
 *
 * <p>整个 Java 工程跟 Python 的耦合面<b>只有这一个类</b>，四个内部接口全在这里：</p>
 * <ul>
 *   <li>{@code /v1/parse} —— 采集攒批 / 导入每 500 行时调，换回模板 + 聚类 id（Step-J4）</li>
 *   <li>{@code /v1/diagnose} —— SSE，三阶段事件流（Step-J6）</li>
 *   <li>{@code /v1/chat} —— SSE，追问直通（Step-J6）</li>
 *   <li>{@code /v1/health} —— 探活，本类现在就有</li>
 * </ul>
 *
 * <p>把它圈在独立的 {@code aiclient} 包、并且只此一个类，目的是：
 * Python 是<b>会随时不在的</b>（说明书 §7 要求降级为 {@code DEGRADED}），
 * 超时、重试、熔断、降级这些逻辑必须都收口在一处，不能渗进 service。</p>
 *
 * <p>用 {@link WebClient} 而不是 RestTemplate：Python 的两个核心接口是 SSE 流式，
 * RestTemplate 会阻塞到流结束才返回，诊断过程就变成"等 40 秒然后一次性刷出来"，实时性全丢。</p>
 */
@Slf4j
@Component

public class PythonClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private final int healthTimeoutMs;
    private final int parseTimeoutMs;

    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_RETRY_INTERVAL_MS = 30_000;

    private volatile int consecutiveFailures = 0;
    private volatile long lastFailureTime = 0;
    private volatile boolean circuitOpen = false;

    public PythonClient(TraceMindProperties properties, ObjectMapper objectMapper) {
        TraceMindProperties.Python python = properties.getPython();
        // 直接 new 而不是注入 WebClient.Builder：这个客户端的 baseUrl 和超时是自有的，
        // 借用全局 Builder 反而会被别处的默认配置悄悄改掉
        this.webClient = WebClient.builder()
                .baseUrl(python.getBaseUrl())
                .build();
        this.objectMapper = objectMapper;
        this.healthTimeoutMs = python.getHealthTimeoutMs();
        this.parseTimeoutMs = python.getParseTimeoutMs();
        log.info("PythonClient 初始化，baseUrl={}, healthTimeoutMs={}, parseTimeoutMs={}",
                python.getBaseUrl(), healthTimeoutMs, parseTimeoutMs);
    }

    /**
     * 探活。Python 不可用属于预期情况，绝不往外抛异常——探活接口的意义就是把"挂了"如实报出来。
     *
     * <p>失败时也返回真实耗时：前端契约里 {@code latencyMs} 不可空，
     * 而且"耗了 2003ms 才超时"和"连接被瞬间拒绝"是两种完全不同的故障，值得区分。</p>
     */
    public HealthProbe health() {
        long start = System.nanoTime();
        try {
            Map<?, ?> body = webClient.get()
                    .uri("/v1/health")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(healthTimeoutMs));

            long costMs = elapsedMs(start);
            log.debug("Python /v1/health 正常，耗时 {}ms", costMs);
            return new HealthProbe(true, costMs, extractLlmOk(body), extractLlmLatencyMs(body), null);
        } catch (Exception e) {
            long costMs = elapsedMs(start);
            log.warn("Python /v1/health 探测失败（耗时 {}ms）: {}", costMs, Utilx.rootMessage(e));
            return new HealthProbe(false, costMs, null, null, Utilx.rootMessage(e));
        }
    }

    /**
     * LLM 状态由 Python 代报——Java 侧不直连 Ollama。
     * Python 的 {@code /v1/health} 若返回 {@code {"llm": {"ok": true}}} 就取出来；
     * 没这个字段就返回 null，表示"未知"，不要伪装成 true。
     */
    private Boolean extractLlmOk(Map<?, ?> body) {
        Object ok = llmField(body, "ok");
        return ok instanceof Boolean llmOk ? llmOk : null;
    }

    /**
     * Python 单独测过 LLM 的耗时（{@code llm.latencyMs}），单位毫秒。
     *
     * <p>这个值本来就是 Python 探活时顺手测的，白白丢掉很可惜：
     * 前端"依赖健康"里 LLM 那一行如果直接复用 Python 请求的总耗时，
     * 就会出现 Python 和 LLM 两行毫秒数一模一样的怪现象 —— 看着像 bug，
     * 实际上是把两个不同的量当成了一个。</p>
     *
     * <p>拿不到时返回 null，由调用方决定要不要退化用总耗时。</p>
     */
    private Long extractLlmLatencyMs(Map<?, ?> body) {
        Object ms = llmField(body, "latencyMs");
        if (ms instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private Object llmField(Map<?, ?> body, String key) {
        if (body == null) {
            return null;
        }
        Object llm = body.get("llm");
        return llm instanceof Map<?, ?> llmMap ? llmMap.get(key) : null;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // rootMessage 已收口到 common/Utilx。此前这里是第一份私有副本：
    // 它只挡了自引用（cause.getCause() == cause），对 A→B→A 这种两元素环照样转不出来。

    /**
     * 探活结果。
     *
     * @param ok           服务是否可用
     * @param latencyMs    本次 HTTP 探活的耗时（毫秒），失败时是"失败前的耗时"，永不为 null
     * @param llmOk        LLM 是否可用；Python 没上报时为 null
     * @param llmLatencyMs Python 单独测得的 LLM 耗时；与上面的 latencyMs 不是一回事，拿不到时为 null
     * @param detail       失败原因，成功时为 null
     */
    public record HealthProbe(boolean ok, long latencyMs, Boolean llmOk, Long llmLatencyMs, String detail) {
    }

    /**
     * 批量模板提取。Python 不可用时<b>不抛异常</b>，返回空列表——
     * 日志先入库，模板留空，比"整批导入失败"好得多。
     *
     * <p>内置简易熔断：连续失败 3 次后熔断 30 秒，期间直接返回空列表，
     * 不再发请求。30 秒后放一个试探请求，恢复了就合闸。</p>
     */
    public List<ParseResult> parse(String projectId, List<String> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }

        if (circuitOpen) {
            if (System.currentTimeMillis() - lastFailureTime < CB_RETRY_INTERVAL_MS) {
                return List.of();
            }
            log.info("熔断冷却结束，试探一次 Python /v1/parse");
        }

        try {
            Map<String, Object> body = Map.of("projectId", projectId == null ? "" : projectId, "logs", logs);
            Map<?, ?> resp = webClient.post()
                    .uri("/v1/parse")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(parseTimeoutMs));

            onCallSuccess();

            if (resp == null) {
                return List.of();
            }
            Object results = resp.get("results");
            if (!(results instanceof List<?> list)) {
                return List.of();
            }
            List<ParseResult> parsed = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    parsed.add(new ParseResult(
                            str(m.get("template")), str(m.get("clusterId"))));
                } else {
                    parsed.add(new ParseResult(null, null));
                }
            }
            return parsed;
        } catch (Exception e) {
            onCallFailure(e);
            return List.of();
        }
    }

    /**
     * 记录一次成功调用。给批量模板提取的 Web 层用，成功时上报一次。
     */
    private void onCallSuccess() {
        if (circuitOpen) {
            log.info("Python /v1/parse 恢复，熔断合闸");
        }
        consecutiveFailures = 0;
        circuitOpen = false;
    }
    /**
     * 记录一次失败调用。给批量模板提取的 Web 层用，失败时上报一次。
     */

    private void onCallFailure(Exception e) {
        consecutiveFailures++;
        lastFailureTime = System.currentTimeMillis();
        if (consecutiveFailures >= CB_FAILURE_THRESHOLD) {
            circuitOpen = true;
            log.warn("Python 连续失败 {} 次，熔断 {}s。原因={}",
                    consecutiveFailures, CB_RETRY_INTERVAL_MS / 1000, Utilx.rootMessage(e));
        }
    }

    /**
     * 当前是否处于熔断状态。给采集线程读，写进 MySQL 快照让前端能看到。
     */
    public boolean isDegraded() {
        return circuitOpen;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * 批量模板提取的返回结果。
     */
    public record ParseResult(String template, String clusterId) {
    }

    /**
     * 触发诊断 —— 返回<b>事件流</b>，不在这里 block。
     *
     * <p>为什么返回 Flux 而不是 List：J6 的核心要求是「边收边推 WS」。
     * 如果在这里 {@code .blockLast()} 等全部跑完再返回，用户就得盯着空屏等十几秒，
     * 诊断过程"逐阶段展开"的效果全没了。</p>
     *
     * <p>三个过滤：<br>
     * ① 空行 —— SSE 帧之间有空行<br>
     * ② {@code [DONE]} —— Python 侧的结束标记，不是事件<br>
     * ③ 解析失败 / type 为空的 —— 丢弃并记日志，不让一个坏帧打断整条流</p>
     *
     * <p><b>刻意不在这里做超时</b>：超时由调用方（DiagnosisServiceImpl）的
     * {@code .timeout(...)} 控制，因为"诊断要跑多久"是业务参数，不是客户端参数。</p>
     */
    public Flux<DiagnosisEventVO> diagnose(String projectId, String sessionId, LogEntryVO triggerLog) {
        return webClient.post()
                .uri("/v1/diagnose")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "projectId", projectId,
                        "sessionId", sessionId,
                        "triggerLog", triggerLog))
                .retrieve()
                .bodyToFlux(String.class)
                .filter(payload -> payload != null && !payload.isBlank())
                .map(String::trim)
                .filter(payload -> !"[DONE]".equals(payload))
                .map(this::parseEvent)
                .filter(Objects::nonNull);
    }

    /**
     * 追问 —— 返回<b>原始 token 流</b>，含 {@code [DONE]}，由 Controller 原样转发给前端。
     *
     * <p>和 {@code diagnose()} 的区别：这里不解析成事件对象。
     * 追问是纯文本 token 流，前端 {@code src/api/diagnosis.ts} 直接读每个 {@code data:} 帧做打字机效果，
     * 后端包一层再拆一层纯属多余。</p>
     */
    public Flux<String> chat(String sessionId, String question, List<ChatMessageVO> history) {
        return webClient.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "sessionId", sessionId,
                        "question", question,
                        "history", history == null ? List.of() : history))
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** 把一行 JSON 解析成事件对象。失败返回 null，由 filter 丢弃 —— 不让坏帧炸掉整条流 */
    private DiagnosisEventVO parseEvent(String payload) {
        try {
            DiagnosisEventVO event = objectMapper.readValue(payload, DiagnosisEventVO.class);
            if (event == null || event.getType() == null || event.getType().isBlank()) {
                log.warn("Python 送来一个没有 type 的事件，已丢弃: {}", abbreviate(payload));
                return null;
            }
            return event;
        } catch (Exception e) {
            log.warn("诊断事件解析失败，已丢弃: {}", abbreviate(payload), e);
            return null;
        }
    }

    private String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 200 ? s : s.substring(0, 200) + "...");
    }

}
