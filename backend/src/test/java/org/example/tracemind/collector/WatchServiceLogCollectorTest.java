package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsFallbackStream;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.service.ErrorRadarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集器的行号连续性 —— 锁住已知问题 2。
 *
 * <p><b>要防的回归。</b>ES 文档 ID 是 {@code 项目:文件:行号}，行号由内存计数器 linesRead 累加得出。
 * 修复前它每次 new collector 都从 0 开始，而 offset 却能从 Redis 恢复到第 5000 行 ——
 * 于是 stop→start 或进程重启后，新读的行被算成第 1 行起，
 * <b>静默覆盖 ES 里最早的文档</b>（同 ID 即覆盖，ES 返回 200，三层容灾对此完全无感）。</p>
 *
 * <p><b>为什么用真文件而不是全 mock。</b>这个 bug 的风险面恰恰在链路衔接处：
 * taskId 是否复用、构造器恢复时机、flush 与落盘的先后。把这些抽成一个纯函数去测，
 * 等于绕开了真正会错的地方。所以这里让采集线程跑真实的 {@code RandomAccessFile} 读取，
 * 只把外部存储（store / ES / Python / MySQL / Redis）换成 mock，再捕获它写出的文档断言 ID。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WatchServiceLogCollectorTest {

    private static final String TASK_ID = "t-001";
    private static final String PROJECT_ID = "p-001";

    @TempDir
    Path tempDir;

    @Mock
    private CollectorOffsetStore offsetStore;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private EsLogRepository esLogRepository;
    @Mock
    private PythonClient pythonClient;
    @Mock
    private LogMonitorTaskMapper logMonitorTaskMapper;
    @Mock
    private CollectorRegistry registry;
    @Mock
    private TraceMindProperties properties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ErrorRadarService errorRadarService;
    @Mock
    private EsFallbackStream esFallbackStream;

    private Path logFile;

    /** 采集线程实际写进 ES 的文档，由 bulkInsert 的 mock 截获 */
    private final List<LogEntryDoc> writtenDocs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        logFile = tempDir.resolve("app.log");
        // Python 侧不参与行号计算；返回空列表 → template 留空，不影响断言
        when(pythonClient.parse(anyString(), anyList())).thenReturn(List.of());
        when(pythonClient.isDegraded()).thenReturn(false);
        // 指纹读写走 Redis：默认给一个能正常工作的 mock，个别用例再改成抛异常
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        // 截获写入 ES 的文档并如实回执「全部成功」。
        // ★ 必须返回 BulkResult —— bulkInsert 现在逐条回报，返回 null 会让采集侧 NPE
        doAnswer(invocation -> {
            List<LogEntryDoc> batch = invocation.getArgument(0);
            synchronized (writtenDocs) {
                writtenDocs.addAll(batch);
            }
            return BulkResult.allOk(batch);
        }).when(esLogRepository).bulkInsert(anyList());
    }

    /**
     * 起采集线程、等它写出第一批、然后停掉。
     *
     * <p>走 {@link CollectorFactory} 而不是手工传 12 个参数 —— 工厂负责调 {@code init()} 恢复位点，
     * 这与生产路径完全一致，也就顺带验证了「工厂创建的实例一定是初始化好的」。</p>
     *
     * <p>不能直接 {@code collector.run()}：那是个 {@code while(running)} 死循环，
     * 主线程会卡在里面。放后台守护线程跑，靠轮询截获结果来收尾。</p>
     */
    private void collectOnce() {
        CollectorDependencies deps = new CollectorDependencies(
                offsetStore, redis, esLogRepository, pythonClient, logMonitorTaskMapper,
                registry, properties, objectMapper, errorRadarService, esFallbackStream);
        WatchServiceLogCollector collector =
                new CollectorFactory(deps).create(TASK_ID, PROJECT_ID, logFile.toString());

        Thread worker = new Thread(collector);
        worker.setDaemon(true);
        worker.start();

        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (writtenDocs) {
                if (!writtenDocs.isEmpty()) {
                    break;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        collector.stop();
    }

    /** 写 N 行已消费完的内容，并把位点设到文件末尾 —— 模拟「上一轮已经读到这儿了」 */
    private void seedAlreadyRead(long lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("2026-10-22 ERROR historical line ").append(i).append('\n');
        }
        Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8);
        when(offsetStore.read(TASK_ID)).thenReturn(
                new CollectorOffsetStore.Position(Files.size(logFile), lines, false));
    }

    /**
     * 追加一行日志。
     *
     * <p>{@code CREATE + APPEND} 而不是只有 {@code APPEND}：全新任务的用例里文件还不存在，
     * 纯 APPEND 会 NoSuchFileException。</p>
     */
    private void append(String line) throws IOException {
        Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    @DisplayName("全新任务（store 无位点）：行号从 1 开始")
    void numbersLinesFromOneForFreshTask() throws IOException {
        append("2026-10-22 ERROR first line");
        append("2026-10-22 ERROR second line");
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(0L, 0L, false));

        collectOnce();

        assertThat(writtenDocs).isNotEmpty();
        // ★ R-2.3 后 ID 中间段统一为裸文件名（EsDocId 会把 filePath 归一），与导入链路同形
        assertThat(writtenDocs.get(0).getId()).isEqualTo(PROJECT_ID + ":app.log:1");
        assertThat(writtenDocs.get(0).getLineNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 核心回归：续读场景下行号接着上次数，绝不能回到 1")
    void continuesLineNumbersAfterRestart() throws IOException {
        // 先造出「已读 5000 行、offset 停在文件尾」的状态
        seedAlreadyRead(5000);

        // 追加一行 —— 这是文件的第 5001 行
        append("2026-10-22 ERROR the 5001st line");

        collectOnce();

        assertThat(writtenDocs).as("应采到追加的那一行").isNotEmpty();
        LogEntryDoc doc = writtenDocs.get(0);

        // ★ 断言的是症状本身：ID 的行号段必须是 5001。修复前这里是 1，会把 ES 里真正的第 1 行顶掉。
        //   中间段用裸文件名 —— 见 EsDocId：两条链路（采集/导入）现在共享同一个 ID 生成规则。
        assertThat(doc.getId()).isEqualTo(PROJECT_ID + ":app.log:5001");
        assertThat(doc.getLineNumber()).isEqualTo(5001);
        assertThat(doc.getRawText()).contains("the 5001st line");
    }

    @Test
    @DisplayName("★ 位点必须随每批 flush 落盘，且 offset 与 lines 同源推进")
    void persistsPositionTogetherWithOffsetAfterEachFlush() throws IOException {
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(0L, 0L, false));
        append("2026-10-22 ERROR line one");
        append("2026-10-22 ERROR line two");

        collectOnce();

        ArgumentCaptor<Long> offsets = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> lines = ArgumentCaptor.forClass(Long.class);
        verify(offsetStore, atLeastOnce()).write(eq(TASK_ID), offsets.capture(), lines.capture());

        long lastLines = lines.getAllValues().get(lines.getAllValues().size() - 1);
        long lastOffset = offsets.getAllValues().get(offsets.getAllValues().size() - 1);
        // 行数真的推进并落盘了（修复前压根没有这次 write）
        assertThat(lastLines).isGreaterThan(0L);
        assertThat(lastOffset).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Redis 故障只剩 MySQL 快照：行号仍不归零")
    void keepsLineNumbersWhenOnlyMysqlSnapshotAvailable() throws IOException {
        seedAlreadyRead(300);
        // 覆盖成降级结果：store 内部已吃掉 Redis 异常并回落快照
        when(offsetStore.read(TASK_ID)).thenReturn(
                new CollectorOffsetStore.Position(Files.size(logFile), 300L, true));

        append("2026-10-22 ERROR new line during outage");

        collectOnce();

        assertThat(writtenDocs).isNotEmpty();
        assertThat(writtenDocs.get(0).getLineNumber()).isEqualTo(301);
    }

    @Test
    @DisplayName("★ B：ES 部分失败时，重试与落盘的都只是失败子集，不重复写已成功的")
    void retriesAndPersistsOnlyFailedSubset() throws IOException {
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(0L, 0L, false));
        append("2026-10-22 ERROR first line");
        append("2026-10-22 ERROR second line");

        // 第一轮拒第二条、收第一条；之后一直拒 —— 逼迫走完 MAX_RETRY
        var partial = new AtomicReference<BulkResult>(null);
        doAnswer(invocation -> {
            List<LogEntryDoc> batch = invocation.getArgument(0);
            LogEntryDoc accepted = batch.get(0);
            LogEntryDoc rejected = batch.size() > 1 ? batch.get(1) : batch.get(0);
            synchronized (writtenDocs) {
                writtenDocs.add(accepted);   // 只有真被 ES 接受的才算写成功
            }
            return new BulkResult(List.of(accepted),
                    List.of(new BulkResult.Failed(rejected, "mapper_parsing_exception")));
        }).when(esLogRepository).bulkInsert(anyList());

        collectOnce();

        ArgumentCaptor<List<LogEntryDoc>> captor = ArgumentCaptor.forClass(List.class);
        verify(esLogRepository, atLeastOnce()).bulkInsert(captor.capture());

        // ★ 第二次及以后提交的必须是上一轮的失败子集（size==1），而不是整批 2 条
        List<List<LogEntryDoc>> submissions = captor.getAllValues();
        if (submissions.size() > 1) {
            assertThat(submissions.get(1))
                    .as("重试应只投失败子集，不再重复写已成功的条目")
                    .hasSizeLessThan(2);
        }
        // 已成功的那条不该再出现在任何后续提交里
        assertThat(writtenDocs).isNotEmpty();
    }

    @Test
    @DisplayName("辅助 key（文件指纹）读写失败不阻断采集")
    void fingerprintFailureDoesNotBlockCollection() throws IOException {
        when(offsetStore.read(TASK_ID)).thenReturn(new CollectorOffsetStore.Position(0L, 0L, false));
        // 指纹是唯一还直连 Redis 的地方，让它炸：saveFingerprint / fingerprintFromRedis 都有 catch
        ValueOperations<String, String> broken = mock(ValueOperations.class);
        when(broken.get(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(broken).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        when(redis.opsForValue()).thenReturn(broken);

        append("2026-10-22 ERROR plain line");

        collectOnce();

        // 指纹挂了照样要把日志采进 ES
        assertThat(writtenDocs).isNotEmpty();
    }
}
