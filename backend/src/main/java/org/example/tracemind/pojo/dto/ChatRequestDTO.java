package org.example.tracemind.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.example.tracemind.pojo.vo.ChatMessageVO;

import java.io.Serializable;
import java.util.List;

/**
 * 追问请求，对应 {@code POST /api/diagnosis/{sessionId}/chat} 的 JSON body。
 * <p>Java 侧只做直通转发：把 question + history 原样交给 Python 的 {@code /v1/chat}，
 * 再把返回的 SSE token 流回吐给前端；回答收完后整条落 {@code chat_message}（role=assistant）。</p>
 */
@Data
public class ChatRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "question 不能为空")
    private String question;

    /** 多轮上下文，前端把历史消息一起带上；可为空 */
    private List<ChatMessageVO> history;
}
