package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.AgentSession;

import java.util.List;
@Mapper
public interface AgentSessionMapper {
    /** 单参数 + {@code @Param("sessionId")}：XML 里写 {@code #{sessionId}}，正确 */
    AgentSession selectById(@Param("sessionId") String sessionId);

    /** 单参数 POJO —— <b>不要</b>加 {@code @Param("session")} */
    int insert(AgentSession session);

    /** 单参数 POJO —— <b>不要</b>加 {@code @Param("session")} */
    int updateResult(AgentSession session);

    /** 两个参数，每个都必须 {@code @Param} */
    int updateStatus(@Param("sessionId") String sessionId,
                     @Param("status") String status);

    int incrToolCall(@Param("sessionId") String sessionId);

    /** 项目下的会话列表。三个参数，每个都要 {@code @Param} */
    List<AgentSession> selectPageByProject(@Param("projectId") String projectId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    long countByProject(@Param("projectId") String projectId);

    /**
     * 跨项目：当前 owner 的全部会话（工作台「最近诊断」用）。
     * agent_session 没有 owner 列，靠子查询 join project 过滤。
     */
    List<AgentSession> selectPageByOwner(@Param("ownerId") String ownerId,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    long countByOwner(@Param("ownerId") String ownerId);

    /** 报告模块要用：一次取多个会话，避免 N+1 */
    List<AgentSession> selectByIds(@Param("sessionIds") List<String> sessionIds);
}
