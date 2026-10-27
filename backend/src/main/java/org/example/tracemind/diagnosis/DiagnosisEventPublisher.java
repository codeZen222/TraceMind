package org.example.tracemind.diagnosis;

import org.example.tracemind.pojo.vo.WsMessageVO;

/**
 * 诊断事件的<b>推送端口</b>：业务侧只说"把这个事件发给订阅该会话的人"，不关心用什么协议、几个连接。
 *
 * <h3>为什么要抽这一层</h3>
 * 此前 {@code WsSessionRegistry} 直接持有 {@code Map<String, Set<jakarta.websocket.Session>>}，
 * 于是「业务要推事件」和「JSR-356 怎么发帧」被焊死在一起：
 * <ul>
 *   <li>{@link DiagnosisEventPipeline} 依赖具体 Registry → 换传输实现（比如迁到 Spring WebSocket）
 *       必须同时改业务侧；</li>
 *   <li>测试想验证推送行为，得连 WebSocket 容器一起起。</li>
 * </ul>
 *
 * <p>有了这个接口，传输方式变成可替换的适配器：现在只有 JSR-356 一个实现；
 * 将来若引入 Spring 的 {@code TextWebSocketHandler}，只需再加一个实现类并切换注册，
 * 流水线与 service 层一行不动。</p>
 *
 * <h3>★ 一条不能丢的语义</h3>
 * {@link #send} 在<b>没有任何连接时必须静默跳过、绝不抛异常</b>。
 * 因为诊断链路的事件是「先落库、再推送」——没人看的时候推送失败不该影响数据完整性，
 * 重连后靠 {@code afterSeq} 从库里补齐。若这里改成抛异常，会把整条诊断流程带崩。
 */
public interface DiagnosisEventPublisher {

    /**
     * 把一条消息推给正在订阅该会话的所有连接。
     *
     * @param sessionId 诊断会话 id
     * @param message   带 seq 的完整帧
     */
    void send(String sessionId, WsMessageVO message);

    /** 当前有多少个连接在监听该会话。0 表示没人看（前端用来决定要不要提示）。 */
    int count(String sessionId);

    /** 全局连接数，用于监控与限流观测。 */
    int totalConnections();
}
