package org.example.tracemind.service;

/**
 * 一次流式回答的<b>输出出口</b>：service 只说"有一帧 / 结束了 / 失败了"，
 * 至于用什么协议送出去，由提供这个对象的调用方决定。
 *
 * <h3>为什么要这一层</h3>
 * 此前 {@code DiagnosisService.chat} 直接返回 {@code SseEmitter}（Spring MVC 的类型），
 * 于是 service 的实现里到处是 {@code emitter.send(SseEmitter.event().data(raw))} ——
 * 业务方法把"该发什么"和"用 SSE 发"焊死在了一起。后果有两个：
 * <ol>
 *   <li><b>测不了</b>：想验证帧序与落库行为，就得构造真的 {@code SseEmitter} 并断言它的内部状态；</li>
 *   <li><b>换不了传输</b>：将来想用 WebSocket 或长轮询推追问结果，只能把整个 {@code streamAnswer} 复制一份
 *       —— 又回到本项目反复出事的形状：<i>同一语义两份实现</i>。</li>
 * </ol>
 *
 * <p>有了这个接口，{@code DiagnosisServiceImpl} 只依赖自己的抽象；
 * controller 提供一个把帧塞进 SSE 的适配器即可。前端契约（{@code data: xxx\n\n} +
 * {@code [DONE]} 结束标记）完全不变。</p>
 *
 * <h3>★ 三条实现约束（写错就会变成线上故障）</h3>
 * <ul>
 *   <li>{@link #emit} 收到的是<b>原样帧</b>，不许 trim —— 英文单词之间的前导空格就是分隔符，
 *       去掉后前端拼出来是 {@code theorderisnotfound} 这种连写（见 README 常见坑 5）。</li>
 *   <li>{@link #done} 与 {@link #fail} <b>互斥且必须有一个被调用</b>。
 *       两个都没调 = 客户端那条 fetch 一直挂着直到超时，这是资源泄漏。</li>
 *   <li>三个方法都可能被<b>非请求线程</b>调用（实现在诊断线程池里跑），
 *       实现方自己保证线程安全。</li>
 * </ul>
 */
public interface StreamSink {

    /**
     * 交付一帧原始内容。可能是正文文本帧，也可能是 {@code tool_call} / {@code tool_result}
     * 这类过程帧 —— 是否入库由 service 决定，sink 只管送出去。
     */
    void emit(String frame);

    /** 流正常结束。实现方应在此送出结束标记并关闭通道。 */
    void done();

    /**
     * 流出错终止。
     *
     * @param cause 失败原因；实现方可以把它转成一条用户可读的错误帧，但<b>不能静默吞掉</b>
     */
    void fail(Throwable cause);
}
