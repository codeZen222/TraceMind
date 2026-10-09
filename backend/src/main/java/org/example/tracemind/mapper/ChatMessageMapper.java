package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.ChatMessage;

import java.util.List;

/**
 * chat_message 表出口 —— 追问（chat）的对话历史。
 *
 * <p>注意 {@code ts} 是 {@code BIGINT} 毫秒时间戳，不是 DATETIME。
 * 前端契约里追问消息的时间就是数字，所以这张表跟着用数字，不做 UTC 转换。</p>
 */
@Mapper
public interface ChatMessageMapper {

    /** 单参数 POJO —— <b>不要</b>加 {@code @Param("message")} */
    int insert(ChatMessage message);

    List<ChatMessage> selectBySession(@Param("sessionId") String sessionId);
}
