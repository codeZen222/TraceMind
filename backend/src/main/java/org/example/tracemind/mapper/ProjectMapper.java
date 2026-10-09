package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.Project;

import java.util.List;

@Mapper
public interface ProjectMapper {

    /**
     * 根据 id 查询项目详情。
     * <p>单参数 + {@code @Param("id")} → XML 里写 {@code #{id}}，正确。</p>
     *
     * <p>⚠️ 多租户：<b>业务入口一律用 {@link #selectByIdAndOwner}，不要用这个方法</b>。
     * 它只留给内部"无条件拿原始记录"的场景（如删除前查关联任务）。</p>
     */
    Project selectById(@Param("id") String id);

    /**
     * 按 id + 归属人查项目。
     * <p>多租户的关键闸门：owner 不匹配直接返回 null，调用方当"项目不存在"处理——
     * 不暴露"存在但你不拥有它"，避免别人枚举项目 id。</p>
     */
    Project selectByIdAndOwner(@Param("id") String id, @Param("ownerId") String ownerId);

    /**
     * 新增项目。
     *
     * <p>⚠️ <b>这里不能加 {@code @Param("project")}</b>。</p>
     * <p>MyBatis 的规则是：<b>方法只有一个参数且该参数是 POJO 时，不加 {@code @Param} 才会把对象本身当参数</b>，
     * XML 里直接写 {@code #{id}}、{@code #{name}}。</p>
     */
    int insert(Project project);

    /**
     * 更新项目。
     */
    int update(Project project);

    /**
     * 删除项目。
     */
    @Delete("DELETE FROM project WHERE id = #{id} AND owner_id = #{ownerId}")
    int deleteByIdAndOwner(@Param("id") String id, @Param("ownerId") String ownerId);

    /**
     * 分页查询项目（强制按 owner 过滤）。
     */
    long countByCondition(@Param("ownerId") String ownerId,
                          @Param("status") String status,
                          @Param("keyword") String keyword);

    List<Project> selectPage(@Param("ownerId") String ownerId,
                             @Param("status") String status,
                             @Param("keyword") String keyword,
                             @Param("offset") int offset,
                             @Param("limit") int limit);
}
