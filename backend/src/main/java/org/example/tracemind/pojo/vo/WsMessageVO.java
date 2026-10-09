package org.example.tracemind.pojo.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * WebSocket 推送与历史回放统一消息壳，契约见说明书 3.2 节 **WsMessage**。
 *
 * <p>{@code seq} 由 Redis 计数器 {@code INCR diag:seq:{sessionId}} 生成，会话内严格自增。
 * 前端断线重连后靠 seq 对齐补齐漏掉的事件，所以这个壳是"可重放"的关键。</p>
 *
 * <p>注意：{@code ts} 用毫秒时间戳（数字），不是 ISO 字符串。</p>
 */
@Data
@NoArgsConstructor
public class WsMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer seq;

    private String sessionId;

    /** 毫秒时间戳 */
    private Long ts;

    /** 事件本体，见 DiagnosisEventVO */
    private DiagnosisEventVO event;

    public static WsMessageVO of(Integer seq, String sessionId, DiagnosisEventVO event) {
        WsMessageVO msg = new WsMessageVO();
        msg.setSeq(seq);
        msg.setSessionId(sessionId);
        msg.setTs(System.currentTimeMillis());
        msg.setEvent(event);
        return msg;
    }

    public static WsMessageVO of(Integer seq, String sessionId, long ts, DiagnosisEventVO event) {
        WsMessageVO msg = new WsMessageVO();
        msg.setSeq(seq);
        msg.setSessionId(sessionId);
        msg.setTs(ts);
        msg.setEvent(event);
        return msg;
    }
}
