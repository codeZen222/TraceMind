package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.Report;

import java.util.List;

/**
 * report 表出口。
 *
 * <p>注意 {@code session_ids} 存的是 <b>JSON 列</b>（MySQL 会校验合法性），
 * Java 侧用 {@code JSON.toJSONString(list)} 转成字符串再写，读出来也是字符串，
 * 组装 VO 时再反序列化成 {@code List<String>}。</p>
 */
@Mapper
public interface ReportMapper {

    /** 单参数 + {@code @Param("id")} */
    Report selectById(@Param("id") String id);

    /** 单参数 POJO —— <b>不要</b>加 {@code @Param("report")} */
    int insert(Report report);

    /**
     * 列表查询。projectId 可选（前端"全部项目"时不传），
     * ownerId 强制（多租户：只查当前用户项目的报告）。
     */
    List<Report> selectPage(@Param("ownerId") String ownerId,
                            @Param("projectId") String projectId,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long countByCondition(@Param("ownerId") String ownerId,
                          @Param("projectId") String projectId);

    /** 项目详情页要显示报告数 */
    long countByProject(@Param("projectId") String projectId);

    int deleteById(@Param("id") String id);
}