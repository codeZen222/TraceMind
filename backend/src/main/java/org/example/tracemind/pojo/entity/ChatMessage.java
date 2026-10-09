package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 追问消息表 chat_message。
 * <p>追问走 Java 直通 Python 的 SSE，token 不逐条落库——整条回答收完后再一次性写入（role=assistant）。</p>
 */
@Data
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，形如 cm-001 */
    private String id;

    private String sessionId;

    /** user | assistant */
    private String role;

    private String content;

    /** 毫秒时间戳（前端契约里就是数字，不走 DATETIME） */
    private Long ts;
}
