package org.example.tracemind.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.common.Utilx;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.dto.ChatRequestDTO;
import org.example.tracemind.pojo.dto.DiagnosisTriggerDTO;
import org.example.tracemind.pojo.vo.DiagnosisSessionVO;
import org.example.tracemind.service.DiagnosisService;
import org.example.tracemind.service.StreamSink;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;
    private final StringRedisTemplate redis;
    private final TraceMindProperties traceMindProperties;

    /** 触发诊断。返回 RUNNING 的会话，后续事件走 WS */
    @PostMapping("/trigger")
    public ApiResponse<DiagnosisSessionVO> trigger(@RequestBody @Valid DiagnosisTriggerDTO dto) {
        return ApiResponse.ok(diagnosisService.trigger(dto));
    }

    /**
     * 会话列表。
     *
     * <p>⚠️ 这个方法必须写在 {@code /{sessionId}} <b>之前</b>吗？不用 ——
     * Spring 的路径匹配优先选字面量 {@code /sessions}，不会把它当成 sessionId。
     * 但为了不让人误读，习惯上把它放在前面。</p>
     */
    /**
     * 会话列表。projectId 可选：不传 = 查当前用户的全部会话（工作台「最近诊断」用）；
     * 传了 = 只查该项目（诊断页会话列表用）。
     */
    @GetMapping("/sessions")
    public ApiResponse<PageResult<DiagnosisSessionVO>> sessions(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(diagnosisService.listSessions(projectId, page, size));
    }

    /** 会话详情（含事件回放） */
    @GetMapping("/{sessionId}")
    public ApiResponse<DiagnosisSessionVO> detail(@PathVariable String sessionId) {
        return ApiResponse.ok(diagnosisService.getById(sessionId));
    }

    /**
     * 追问，SSE 流式返回。
     *
     * <p>三点注意：</p>
     * <ol>
     *   <li>{@code produces = TEXT_EVENT_STREAM_VALUE} 不能省，否则前端 eventSource 认不出</li>
     *   <li>返回的是 {@link SseEmitter}，<b>不套 ApiResponse</b> —— 流没法包装成 {code,message,data}</li>
     *   <li>这是全项目第二个不走统一响应的接口（另一个是报告下载）</li>
     * </ol>
     *
     * <h3>★ 为什么这里要造 emitter 并包一层适配器</h3>
     * service 的 {@code chat} 已不再认识 {@code SseEmitter}（此前它写在 service 接口上，
     * 等于把抽象层锁死在 Spring MVC）。<b>传输选择是 web 层的决定</b>：controller 负责造
     * emitter、提供"帧 → SSE 报文"的适配器；service 只管业务与落库。
     *
     * <p>对外契约一字未改：仍是 {@code data: <帧>}，仍以 {@code [DONE]} 收尾。</p>
     */
    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String sessionId, @RequestBody @Valid ChatRequestDTO dto) {
        // 180s：LLM 最长回答时间 + 余量
        SseEmitter emitter = new SseEmitter(180_000L);
        diagnosisService.chat(sessionId, dto, new SseStreamSink(emitter));
        return emitter;
    }

    /**
     * 把 service 的三类事件翻译成 SSE 报文。
     *
     * <p>⚠️ 三条约束来自 {@link StreamSink} 契约：帧原文不许 trim（前导空格是英文分词）、
     * done/fail 互斥且必有一个被调用、本类会被诊断线程调用 —— {@code SseEmitter} 对并发
     * send 不做保护，故对同一 emitter 的写入串行化。</p>
     */
    private static final class SseStreamSink implements StreamSink {

        private final SseEmitter emitter;
        private final Object lock = new Object();

        SseStreamSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void emit(String frame) {
            synchronized (lock) {
                try {
                    // 原样送出，绝不 trim —— 见 README 常见坑 5
                    emitter.send(SseEmitter.event().data(frame));
                } catch (Exception e) {
                    // 多半是客户端提前断开。终止由 service 侧统一负责，这里只记录不打断流。
                    log.warn("SSE 发送失败（可能客户端已断开）", e);
                }
            }
        }

        @Override
        public void done() {
            synchronized (lock) {
                try {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                } catch (Exception e) {
                    log.debug("SSE 终止帧发送失败", e);
                }
                emitter.complete();
            }
        }

        @Override
        public void fail(Throwable cause) {
            synchronized (lock) {
                /*
                 * 刻意不用 completeWithError：那会让异常冒到全局 handler，可响应头早已发出、
                 * body 也已开写，最终只能回一个空体 —— 前端 fetch 读不到原因，只显示"请求失败"。
                 * 改成把原因作为一帧文本送出去，用户能看到到底哪儿坏了。
                 */
                String reason = Utilx.rootMessage(cause);
                try {
                    emitter.send(SseEmitter.event().data("AI 服务暂时不可用：" + reason));
                } catch (Exception ignored) {
                    // 连接已断，发不出去也没有别的办法
                }
                emitter.complete();
            }
        }
    }

    /**
     * 签发 WebSocket 握手 ticket。
     *
     * <p>当 {@code tracemind.websocket.enable-auth} 为 false 时，
     * 这个接口<b>仍然返回 ticket</b>但 WS 端点不校验它——前端代码不用分支判断。</p>
     *
     * <p>返回的 ticket 是一次性的：WS 握手时 Redis 里 getAndDelete，不会被重放。
     * 存 60s TTL，前端拿到后 1 分钟内必须连 WS（正常流程毫秒级完成）。</p>
     */
    @PostMapping("/{sessionId}/ws-ticket")
    public ApiResponse<Map<String, String>> issueWsTicket(@PathVariable String sessionId) {
        // ★ 多租户：拿 ticket 前先校验会话归属，否则等于把别人诊断的实时过程拱手让人
        diagnosisService.requireOwnedSession(sessionId);

        // WS 鉴权关闭时不需要 ticket，也就不依赖 Redis —— 别为了发一个没人验的字符串把接口打成 50000
        boolean authEnabled = traceMindProperties.getWebsocket().isEnableAuth();
        if (!authEnabled) {
            return ApiResponse.ok(Map.of("ticket", ""));
        }

        String ticket = UUID.randomUUID().toString().replace("-", "");
        try {
            // key 与 TTL 都取自 RedisKeys：签发端和握手校验端必须完全一致，
            // 否则 getAndDelete 恒 null、WS 全量静默鉴权失败（见 RedisKeys#wsTicket）
            redis.opsForValue().set(RedisKeys.wsTicket(ticket), sessionId,
                    RedisKeys.WS_TICKET_TTL.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // ticket 存在 Redis 里，Redis 挂了就是发不出来。此时明确告诉前端「现在连不上 WS」，
            // 比让它拿到一个假 ticket、再到握手阶段被拒更好——后者从报错上看不出根因。
            log.warn("签发 WS ticket 失败（Redis 不可用）sessionId={} 原因={}", sessionId, e.getMessage());
            throw BizException.serviceUnavailable("实时通道暂不可用，请稍后重试");
        }
        return ApiResponse.ok(Map.of("ticket", ticket));
    }
}