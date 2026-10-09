package org.example.tracemind.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集线程登记表：taskId → 正在跑的采集器。
 *
 * <p>为什么需要它：采集是异步启的（Spring 不给线程句柄），
 * 但 {@code MonitorService.stop()} 必须能<b>找到那个线程</b>才能让它停。</p>
 *
 * <p>用 {@link ConcurrentHashMap}：start / stop 是两个不同的 HTTP 请求，会并发读写。</p>
 */
@Slf4j
@Component
public class CollectorRegistry {

    private final Map<String, WatchServiceLogCollector> collectors = new ConcurrentHashMap<>();

    /** 登记。同一个 taskId 重复登记会覆盖——调用方要先保证幂等 */
    public void register(String taskId, WatchServiceLogCollector collector) {
        collectors.put(taskId, collector);
        log.info("采集器已登记 taskId={}，当前共 {} 个", taskId, collectors.size());
    }

    /** 注销。线程退出时自己调（放在 finally 里） */
    public void unregister(String taskId) {
        collectors.remove(taskId);
        log.info("采集器已注销 taskId={}，当前剩 {} 个", taskId, collectors.size());
    }

    /** 取不到返回 null，调用方自己判 */
    public WatchServiceLogCollector get(String taskId) {
        return collectors.get(taskId);
    }

    /** "在不在跑"以登记表为准，不去问线程状态 */
    public boolean isRunning(String taskId) {
        return collectors.containsKey(taskId);
    }
}