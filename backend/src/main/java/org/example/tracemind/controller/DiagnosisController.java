package org.example.tracemind.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.dto.ChatRequestDTO;
import org.example.tracemind.pojo.dto.DiagnosisTriggerDTO;
import org.example.tracemind.pojo.vo.DiagnosisSessionVO;
import org.example.tracemind.service.DiagnosisService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
     */
    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String sessionId, @RequestBody @Valid ChatRequestDTO dto) {
        return diagnosisService.chat(sessionId, dto);
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
        String ticket = UUID.randomUUID().toString().replace("-", "");
        long ttlSeconds = 60L;
        redis.opsForValue().set("ws:ticket:" + ticket, sessionId, ttlSeconds, TimeUnit.SECONDS);
        return ApiResponse.ok(Map.of("ticket", ticket));
    }
}