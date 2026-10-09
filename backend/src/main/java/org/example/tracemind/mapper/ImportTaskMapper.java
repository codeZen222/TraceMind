package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.ImportTask;

/**
 * import_task 表出口，只服务 Step-J4 的两个接口。
 *
 * <p>这个接口巧了——MyBatis 的两条<b>相反</b>规则在这儿各出现一次，写的时候对着看：</p>
 * <ul>
 *   <li>单参数 POJO → <b>不加</b> {@code @Param}（看 {@link #insert}）</li>
 *   <li>多参数 → <b>每个都要</b> {@code @Param}（看 {@link #updateProgress}）</li>
 * </ul>
 */
@Mapper
public interface ImportTaskMapper {

    /** 单参数 + {@code @Param("taskId")}：XML 里写 {@code #{taskId}}，正确 */
    ImportTask selectById(@Param("taskId") String taskId);

    /**
     * 插入新任务。单参数 POJO。
     *
     * <p>⚠️ <b>千万不要加 {@code @Param("task")}</b>。</p>
     * <p>加了之后 MyBatis 会把它包成 {@code ParamMap{task=..., param1=...}}，
     * XML 里就<b>必须</b>改写成 {@code #{task.taskId}}；仍写 {@code #{taskId}} 会全部取到 null，
     * SQL 变成 {@code INSERT ... VALUES (NULL, NULL, ...)}，
     * MySQL 报 <b>{@code Column 'task_id' cannot be null}</b>，
     * 最后被全局异常处理器兜底成 {@code code:50000}——报错信息里完全看不出跟 {@code @Param} 有关。</p>
     */
    int insert(ImportTask task);

    /**
     * 更新导入进度。
     *
     * <p>每 500 行调一次，是 J4 里最频繁的写库操作。</p>
     * <p>三个参数，<b>每个都必须 {@code @Param}</b>，漏一个就报
     * {@code Parameter 'xxx' not found. Available parameters are [arg0, arg1, param1, param2]}。</p>
     */
    int updateProgress(@Param("taskId") String taskId,
                       @Param("processed") int processed,
                       @Param("status") String status);

    /** 启动时清理僵尸任务：将所有 RUNNING 改为 FAILED */
    int updateRunningToFailed();

    /** 查找指定项目下正在执行的导入任务 */
    ImportTask findRunningByProjectId(@Param("projectId") String projectId);

    int deleteByProjectId(@Param("projectId") String projectId);
}