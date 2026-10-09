package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.AgentEvent;

import java.util.List;

@Mapper
public interface AgentEventMapper {
    /**
     * 单参数 POJO —— <b>不要</b>加 {@code @Param("event")}。
     *
     * <p>{@code id} 是自增主键，由 DB 填。加了 {@code useGeneratedKeys} 之后
     * MyBatis 会把生成的值写回 {@code event.getId()}，但我们不等它 —— 推 WS 用的是 {@code seq}，
     * 不是数据库主键。</p>
     */
    int insert(AgentEvent event);

    /** 详情接口的历史回放：全量、按 seq 升序。单参数 + {@code @Param} */
    List<AgentEvent> selectBySession(@Param("sessionId") String sessionId);

    /** 断线重连补发：只要 seq 比客户端已有的大的。两个参数，都要 {@code @Param} */
    List<AgentEvent> selectAfterSeq(@Param("sessionId") String sessionId,
                                    @Param("afterSeq") int afterSeq);
}
