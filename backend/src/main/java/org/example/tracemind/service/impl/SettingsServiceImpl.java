package org.example.tracemind.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.diagnosis.AutoDiagnosisSwitch;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.pojo.vo.HealthItemVO;
import org.example.tracemind.pojo.vo.ModelInfoVO;
import org.example.tracemind.service.SettingsService;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 依赖探活实现。
 *
 * <h3>为什么每个探针都自己 try/catch</h3>
 * 探活接口要是"一个依赖挂了就整个 500"，它就失去意义了——用户看到的是白屏错误页，
 * 而不是"ES 挂了、MySQL 正常"这个真正有用的信息。所以每个探针独立兜底，失败只体现为
 * {@code ok=false}，绝不外抛。
 *
 * <h3>为什么并行探测</h3>
 * 五个依赖串行探测，最坏情况是各依赖超时时间之和（2+2+2+2 = 8 秒以上）。
 * 探活接口是给人看的诊断工具，自己卡 8 秒就本末倒置了。
 * 用独立线程池并行后，总耗时 = <b>最慢那一项</b>，且互不干扰。
 *
 * <p>刻意<b>不</b>共用 {@code AsyncConfig} 的诊断线程池：探活是阻塞式 IO，
 * 丢进诊断池会和真正的诊断任务抢线程，而诊断池的行为约束（CallerRuns）也不是给探活准备的。</p>
 *
 * <h3>为什么探活超时必须设得很短</h3>
 * 三个依赖的连接超时都在 yml 里压到了 2~3 秒。理由同上：
 * 宁可 2 秒给出"MySQL 不可用"的结论，也不要 30 秒后给出同一句话。
 * 超时配置见 {@code spring.datasource.hikari.connection-timeout}、
 * {@code spring.data.redis.timeout}、{@code spring.elasticsearch.*-timeout}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    private static final AtomicInteger PROBE_THREAD_SEQ = new AtomicInteger();

    private final DataSource dataSource;

    private final StringRedisTemplate stringRedisTemplate;

    private final ElasticsearchClient elasticsearchClient;

    private final PythonClient pythonClient;

    private final TraceMindProperties properties;

    private final AutoDiagnosisSwitch autoDiagnosisSwitch;

    /** 探活专用线程池，大小 = 探测项数，保证五项同时开跑 */
    private final ExecutorService probePool = Executors.newFixedThreadPool(5, runnable -> {
        Thread thread = new Thread(runnable, "health-probe-" + PROBE_THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdownProbePool() {
        probePool.shutdownNow();
    }

    @Override
    public List<HealthItemVO> health() {
        CompletableFuture<HealthItemVO> mysql = CompletableFuture.supplyAsync(this::probeMysql, probePool);
        CompletableFuture<HealthItemVO> redis = CompletableFuture.supplyAsync(this::probeRedis, probePool);
        CompletableFuture<HealthItemVO> elasticsearch = CompletableFuture.supplyAsync(this::probeElasticsearch, probePool);

        // Python 与 LLM 共用一次 HTTP 调用：LLM 的状态本来就是 Python 代报的，
        // 分两次请求既慢，又可能出现"Python 说 LLM 行、第二次探测说不通"的自相矛盾
        CompletableFuture<PythonClient.HealthProbe> python = CompletableFuture.supplyAsync(pythonClient::health, probePool);

        // 顺序固定：前端把数组按 index 渲染成一排指示灯，乱序灯位会跳
        List<HealthItemVO> items = new ArrayList<>(5);
        items.add(await(mysql, "MySQL"));
        items.add(await(redis, "Redis"));
        items.add(await(elasticsearch, "Elasticsearch"));

        PythonClient.HealthProbe pythonProbe = awaitPython(python);
        items.add(HealthItemVO.of("Python", pythonProbe.ok(), pythonProbe.latencyMs()));
        items.add(probeLlm(pythonProbe));

        return items;
    }

    @Override
    public ModelInfoVO modelInfo() {
        TraceMindProperties.Model model = properties.getModel();
        ModelInfoVO vo = new ModelInfoVO();
        vo.setProvider(model.getProvider());
        vo.setModel(model.getModel());
        vo.setBaseUrl(model.getBaseUrl());
        return vo;
    }

    @Override
    public Map<String, Object> autoDiagnosisStatus() {
        return Map.of(
                "enabled", autoDiagnosisSwitch.isEnabled(),
                "limit", autoDiagnosisSwitch.getLimit());
    }

    @Override
    public void setAutoDiagnosis(boolean enabled) {
        autoDiagnosisSwitch.setEnabled(enabled);
    }

    private HealthItemVO probeMysql() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return HealthItemVO.of("MySQL", valid, elapsedMs(start));
        } catch (Exception e) {
            log.warn("MySQL 探活失败: {}", e.getMessage());
            return HealthItemVO.of("MySQL", false, elapsedMs(start));
        }
    }

    private HealthItemVO probeRedis() {
        long start = System.nanoTime();
        try (RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            boolean ok = "PONG".equalsIgnoreCase(pong);
            return HealthItemVO.of("Redis", ok, elapsedMs(start));
        } catch (Exception e) {
            log.warn("Redis 探活失败: {}", e.getMessage());
            return HealthItemVO.of("Redis", false, elapsedMs(start));
        }
    }

    private HealthItemVO probeElasticsearch() {
        long start = System.nanoTime();
        try {
            BooleanResponse response = elasticsearchClient.ping();
            return HealthItemVO.of("Elasticsearch", response.value(), elapsedMs(start));
        } catch (Exception e) {
            log.warn("Elasticsearch 探活失败: {}", e.getMessage());
            return HealthItemVO.of("Elasticsearch", false, elapsedMs(start));
        }
    }

    /**
     * LLM 状态由 Python 代报。三种情况要分清楚，不能混：
     * <ul>
     *   <li>Python 不在 → LLM 也无从得知，报 false（不是"未知"，因为整条链路确实断了）</li>
     *   <li>Python 在但没上报 llm 字段 → 报 false</li>
     *   <li>Python 上报了 → 用它的结果</li>
     * </ul>
     *
     * <p><b>耗时用 llmLatencyMs，不要复用 latencyMs。</b>
     * 后者是"请求 Python 的 /v1/health 花了多久"（含 Python 自己探 MySQL/Redis/ES 的时间），
     * 前者才是"LLM 这一次往返花了多久"。两个都直接显示的话，
     * 界面上 Python 和 LLM 两行会是同一个数字 —— 用户第一反应就是"这不是假的吧"。</p>
     */
    private HealthItemVO probeLlm(PythonClient.HealthProbe pythonProbe) {
        if (!pythonProbe.ok()) {
            return HealthItemVO.of("LLM", false, pythonProbe.latencyMs());
        }
        boolean llmOk = Boolean.TRUE.equals(pythonProbe.llmOk());
        // Python 没单独报 LLM 耗时（老版本 / 探测降级）时退回总耗时，保证 latencyMs 不为 null
        long latency = pythonProbe.llmLatencyMs() != null
                ? pythonProbe.llmLatencyMs()
                : pythonProbe.latencyMs();
        return HealthItemVO.of("LLM", llmOk, latency);
    }

    /** 单个探针理论上不会抛（自己兜了），这里再兜一层，防止线程池被拒等意外把整个接口带崩 */
    private HealthItemVO await(CompletableFuture<HealthItemVO> future, String name) {
        try {
            return future.join();
        } catch (Exception e) {
            log.error("{} 探活任务异常", name, e);
            return HealthItemVO.of(name, false, 0L);
        }
    }

    private PythonClient.HealthProbe awaitPython(CompletableFuture<PythonClient.HealthProbe> future) {
        try {
            return future.join();
        } catch (Exception e) {
            log.error("Python 探活任务异常", e);
            return new PythonClient.HealthProbe(false, 0L, null, null, e.getMessage());
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
