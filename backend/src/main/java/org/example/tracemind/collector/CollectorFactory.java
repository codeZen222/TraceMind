package org.example.tracemind.collector;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 采集线程的创建入口。
 *
 * <p>{@link WatchServiceLogCollector} 每任务一个实例、且要在独立线程里跑，不能做成单例 Bean；
 * 但它又需要一堆容器 Bean。本类把这两件事分开：</p>
 * <ul>
 *   <li><b>容器 Bean</b>：全部收在 {@link CollectorDependencies} 里，由 Spring 注入 —— 缺一个就启动失败</li>
 *   <li><b>per-task 值</b>：只有 taskId / projectId / filePath 三个，作为方法参数显式传入</li>
 * </ul>
 *
 * <p>这样调用方（{@code MonitorServiceImpl}）不再需要持有那堆自己用不到的依赖，
 * 也不会出现"12 个参数靠位置对齐"的错注风险。</p>
 */
@Component
@RequiredArgsConstructor
public class CollectorFactory {

    private final CollectorDependencies dependencies;

    /**
     * 创建一个<b>已初始化</b>的采集线程实例（尚未启动）。调用方负责把它交给线程池。
     *
     * @param taskId    采集任务 id，同时是位点 key 的一部分
     * @param projectId 所属项目，用于 ES 文档 ID 与雷达判新
     * @param filePath  被监控的日志文件绝对路径
     */
    public WatchServiceLogCollector create(String taskId, String projectId, String filePath) {
        WatchServiceLogCollector collector = new WatchServiceLogCollector(taskId, projectId, filePath, dependencies);
        // ★ 由工厂统一负责初始化，避免"已构造但未恢复位点"的中间态被误用 ——
        //   那种状态下 linesRead 是 0，续读时新行会被算成第 1 行起、静默覆盖 ES 里最早的文档。
        //   放在这里而不是构造器：构造期不该做 I/O（会让对象无法脱离外部系统被创建／测试）。
        collector.init();
        return collector;
    }
}
