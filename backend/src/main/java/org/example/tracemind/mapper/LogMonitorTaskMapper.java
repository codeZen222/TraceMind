package org.example.tracemind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.tracemind.pojo.entity.LogMonitorTask;

import java.util.List;

/**
 * log_monitor_task 表出口。
 *
 * <p>⚠️ 这张表里也有 {@code offset} 字段，但它是<b>兜底快照</b>，
 * 真身在 Redis 的 {@code offset:{taskId}}。查状态时要优先用 Redis 的值。</p>
 */
@Mapper
public interface LogMonitorTaskMapper {

    /** 按 taskId 查。查不到返回 null */
    LogMonitorTask selectById(@Param("taskId") String taskId);

    /** 按项目查。一个项目理论上只有一个任务 */
    LogMonitorTask selectByProjectId(@Param("projectId") String projectId);

    /** 单参数 POJO → ⚠️ 不加 @Param（加了两边都得改，见 J2 那个坑） */
    int insert(LogMonitorTask task);

    /** 更新运行状态与统计。采集线程每 30 秒回写一次 */
    int updateStatus(LogMonitorTask task);

    List<LogMonitorTask> selectAllRunning();

    int deleteByProjectId(@Param("projectId") String projectId);
}