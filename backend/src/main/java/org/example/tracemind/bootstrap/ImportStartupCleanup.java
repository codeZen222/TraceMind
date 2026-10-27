package org.example.tracemind.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.mapper.ImportTaskMapper;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;


/**
 * 服务启动时清理「僵尸导入任务」。
 *
 * <p>如果服务在导入过程中宕机，MySQL 里会留下 status = 'RUNNING' 的记录，
 * 前端轮询永远等不到终态。启动时将这类记录统一标记为 FAILED，
 * 用户看到后可以手动重试。</p>
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStartupCleanup implements ApplicationListener<ApplicationStartedEvent> {

    private final ImportTaskMapper importTaskMapper;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        // 启动时清理「僵尸导入任务」
        int cleaned = importTaskMapper.updateRunningToFailed();
        if (cleaned > 0) {
            log.warn("启动时清理了 {} 个僵尸导入任务（status=RUNNING → FAILED）", cleaned);
        } else {
            log.info("启动检查：无僵尸导入任务");
        }
    }
}