package org.example.tracemind.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.service.MonitorService;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务启动后自动恢复所有 running=true 的采集任务。
 *
 * <p>解决"进程重启 → 采集线程全丢 → MySQL 悬空"的问题。
 * 启动时扫一遍 log_monitor_task，把该跑的重新拉起来。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorRecoveryRunner implements ApplicationRunner {

    private final LogMonitorTaskMapper logMonitorTaskMapper;
    private final MonitorService monitorService;

    @Override
    public void run(ApplicationArguments args) {
        List<LogMonitorTask> tasks = logMonitorTaskMapper.selectAllRunning();
        if (tasks == null || tasks.isEmpty()) {
            log.info("无需恢复采集任务");
            return;
        }

        log.info("发现 {} 个悬空采集任务，开始恢复", tasks.size());
        for (LogMonitorTask task : tasks) {
            try {
                monitorService.start(task.getProjectId());
                log.info("采集任务已恢复 taskId={} projectId={}", task.getTaskId(), task.getProjectId());
            } catch (Exception e) {
                log.error("采集任务恢复失败 taskId={} projectId={}", task.getTaskId(), task.getProjectId(), e);
            }
        }
    }
}