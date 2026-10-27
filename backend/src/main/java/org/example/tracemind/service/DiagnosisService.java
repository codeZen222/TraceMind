package org.example.tracemind.service;

import org.example.tracemind.common.PageResult;
import org.example.tracemind.pojo.dto.ChatRequestDTO;
import org.example.tracemind.pojo.dto.DiagnosisTriggerDTO;
import org.example.tracemind.pojo.vo.DiagnosisSessionVO;

/**
 * 诊断域业务接口。
 *
 * <p>注意 {@code handleEvent} <b>不在</b>这个接口里 —— 它是实现内部的方法。
 * 对外只有"触发、查详情、查列表、追问"四件事。</p>
 *
 * <p>★ 本接口的所有方法都不出现 Web 传输类型（此前 {@code chat} 返回
 * {@code SseEmitter}，等于把抽象层锁死在 Spring MVC 上）。流的交付方式由调用方通过
 * {@link StreamSink} 注入，见该接口的说明。</p>
 */
public interface DiagnosisService {

    /** 触发一次诊断。立即返回 RUNNING 的会话对象，诊断过程靠 WS 推 */
    DiagnosisSessionVO trigger(DiagnosisTriggerDTO dto);

    /**
     * 自动诊断内部版：不做归属校验（调用方在导入/采集入口已校验过归属）。
     * 供新错误雷达在异步线程里触发诊断。返回 sessionId；日志不存在/不属于项目时返回 null。
     */
    String triggerAuto(String projectId, String logId);

    /** 详情（含历史事件回放） */
    DiagnosisSessionVO getById(String sessionId);

    /** 项目下的会话分页 */
    PageResult<DiagnosisSessionVO> listSessions(String projectId, Integer page, Integer size);

    /**
     * 追问。<b>同步部分</b>只有归属校验、参数校验和用户消息落库；
     * 流式交付在异步线程里通过 {@code sink} 回调出去。
     *
     * <p>⚠️ 本方法<b>不保证</b>在返回前结束流 —— 调用方必须按"异步"对待这个 sink：
     * 通道一直开着，直到收到 {@link StreamSink#done()} 或 {@link StreamSink#fail(Throwable)}
     * 二者之一（互斥且必有一个被调用）。</p>
     */
    void chat(String sessionId, ChatRequestDTO dto, StreamSink sink);

    /**
     * 校验会话归属当前用户（不存在 / 不拥有都抛 404）。
     * 供 WebSocket ticket 签发这类"只有 sessionId、没有 projectId"的入口做鉴权。
     */
    void requireOwnedSession(String sessionId);
}