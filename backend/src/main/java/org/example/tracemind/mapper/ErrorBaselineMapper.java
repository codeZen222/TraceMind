package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.ErrorBaseline;

import java.util.List;

/**
 * error_baseline 表出口 —— 新错误雷达的「已见指纹」存储。
 *
 * <p>⚠️ 所有方法都 ≥2 参数或单 POJO，MyBatis 两条规则对着看：</p>
 * <ul>
 *   <li>多参数 → <b>每个都要 {@code @Param}</b>（漏一个就报 Parameter not found）</li>
 *   <li>单 POJO → 不要加 {@code @Param}</li>
 * </ul>
 */
@Mapper
public interface ErrorBaselineMapper {

    /** 项目已记录的基线条数。雷达用它判断「冷启动」：==0 表示第一次导入，全建基线不报新 */
    long countByProject(@Param("projectId") String projectId);

    /** 某个错误指纹是否已在基线里。返回 0/1 */
    int exists(@Param("projectId") String projectId, @Param("clusterId") String clusterId);

    /**
     * 写入基线（判新命中：新发现的错误）。acknowledged 默认 0 = 未读，会被 newErrors 列出。
     * INSERT IGNORE：并发判新时唯一键冲突静默跳过，不打断主流程。
     */
    int insert(@Param("projectId") String projectId, @Param("clusterId") String clusterId);

    /**
     * 写入基线（冷启动：第一次导入建立历史基线）。acknowledged=1 = 已读。
     * 冷启动的模板是「历史已知」的，不是「新告警」，不能进 newErrors 刷屏。
     */
    int insertBaseline(@Param("projectId") String projectId, @Param("clusterId") String clusterId);

    /** 查项目「未读」的新错误（按首次出现倒序）。前端新错误面板用 */
    List<ErrorBaseline> selectUnacked(@Param("projectId") String projectId);

    /** 标记已读（前端「知道了」按钮） */
    int ack(@Param("projectId") String projectId, @Param("clusterId") String clusterId);
}
