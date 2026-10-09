package org.example.tracemind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池。所有 AI 相关的长任务都跑在这里，绝不占用 Tomcat 的请求线程。
 *
 * <p>参数来自说明书 2.3 硬性约束：核心 4 / 最大 8 / 队列 100 / CallerRuns + 日志告警。</p>
 *
 * <h3>为什么拒绝策略不是 AbortPolicy</h3>
 * 队列满了就丢任务 → 用户点了"诊断"但永远等不到结果，前端一直转圈，比慢一点难受得多。
 * {@link ThreadPoolExecutor.CallerRunsPolicy} 让任务退回调用线程执行，
 * <b>宁可把调用方拖慢，也不丢诊断请求</b>。代价是如果调用方是 Tomcat 线程，
 * 高负载下会连带把 HTTP 响应拖慢——这是刻意的取舍，不是疏漏。
 * 标准 CallerRunsPolicy 是静默的，这里包一层把"池子打满"这件事打成 error 日志，
 * 否则系统在满载退化时是没有任何信号的。</p>
 *
 * <h3>为什么 waitForTasksToCompleteOnShutdown</h3>
 * 诊断跑到一半重启服务，直接把线程掐了会让会话永远停在 RUNNING（数据库里没有终态）。
 * 等 30 秒让在途任务收尾，会话至少能落个终态；真等不完再由各自的超时兜底。</p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 诊断编排专用池名，{@code @Async(AsyncConfig.DIAGNOSIS_EXECUTOR)} 引用它 */
    public static final String DIAGNOSIS_EXECUTOR = "diagnosisExecutor";
    // 导入专用池名，{@code @Async(AsyncConfig.IMPORT_EXECUTOR)} 引用它
    public static final String IMPORT_EXECUTOR = "importExecutor";

    /**
     * 日志导入专用线程池。
     *
     * <p>为什么要和诊断分开：导入是长任务（大文件几分钟），诊断是短任务（几十秒）。
     * 共用一个池的话，一个大文件导入能把诊断的线程全占满，用户点"诊断"就一直转圈。</p>
     *
     * <p>队列满用 {@code CallerRunsPolicy}：宁可让调用线程变慢，也不丢任务。</p>
     */
    @Bean(name = IMPORT_EXECUTOR)
    public ThreadPoolTaskExecutor importExecutor() {
        // 导入专用线程池，核心 2 / 最大 4 / 队列 50 / CallerRuns + 日志告警
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);          // 导入不需要太多并发
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("tm-import-");
        // 队列满用 CallerRunsPolicy
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 等待所有任务完成再关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);   // 大文件可能比诊断久，给 60 秒
        executor.initialize();
        return executor;
    }
    /**
     * 诊断编排专用线程池。
     *
     * <p>队列满用 {@code CallerRunsPolicy}：宁可让调用线程变慢，也不丢任务。</p>
     */

    @Bean(name = DIAGNOSIS_EXECUTOR)
    public ThreadPoolTaskExecutor diagnosisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("diag-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            log.error("诊断线程池已饱和，任务改由调用线程执行。activeCount={}, queueSize={}, poolSize={}",
                    pool.getActiveCount(), pool.getQueue().size(), pool.getPoolSize());
            if (!pool.isShutdown()) {
                task.run();
            }
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    public static final String COLLECTOR_EXECUTOR = "collectorExecutor";

    /**
     * 采集线程池。
     *
     * <p>⚠️ 采集线程是<b>长驻</b>的（一个任务一个死循环），核心线程会被一直占住，
     * 所以这个池要有足够的 max —— 上限约等于"同时监听的项目数"。</p>
     */
    @Bean(name = COLLECTOR_EXECUTOR)
    public ThreadPoolTaskExecutor collectorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(32);          // 同时监听的项目上限
        executor.setQueueCapacity(0);         // 不排队，直接起新线程
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("tm-collector-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    public static final String AUTO_DIAGNOSIS_EXECUTOR = "autoDiagnosisExecutor";

    /**
     * 自动诊断的「等诊断完成 + 生成报告」专用池。
     *
     * <p>单线程：等待任务是低频长尾的（每 2s 轮询一次，最长 180s），不需要并发，
     * 单线程串行处理足够，也避免并发批次把别的池占满。</p>
     */
    @Bean(name = AUTO_DIAGNOSIS_EXECUTOR)
    public ThreadPoolTaskExecutor autoDiagnosisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tm-auto-diag-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
