package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 追问消息，契约见说明书 3.2 节 **ChatMessage**。
 *
 * <p>两个用途：① {@code ChatRequestDTO.history} 的多轮上下文；② {@code chat_message} 表读写。
 * 因为契约里就是同一份结构，所以只定义这一个类，不重复造。</p>
 */
@Data
public class ChatMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 形如 cm-001 */
    private String id;

    /** user | assistant */
    private String role;

    private String content;

    /** 毫秒时间戳 */
    private Long ts;
}
