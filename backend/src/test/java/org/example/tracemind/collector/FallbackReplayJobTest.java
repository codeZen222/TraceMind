package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.entity.LogEntryDoc;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 兜底文件重放作业的处置正确性 —— 锁住已知问题 A1 / A2。
 *
 * <p><b>A1（部分失败误删文件）。</b>ES bulk 的部分失败是 HTTP 200 + errors=true，不抛异常。
 * 原先"bulkInsert 没抛就删文件"的写法会让一个文件里几条写不进去的文档<b>随整个文件一起消失</b>，
 * 且永远不会再重试。典型触发场景：兜底文件是几周前写的，期间索引 mapping 收紧过。</p>
 *
 * <p><b>A2（一行坏 JSON 卡死整个文件）。</b>原先整个读取包在一个 try 里、任一行解析失败即 return，
 * 于是进程崩溃时写了一半的那一行，会让同文件其余所有正常数据永远无法回灌，并每分钟失败一次无限循环。</p>
 *
 * <p>这个类依赖很干净（repository + properties + mapper），用 {@code @TempDir} 起真目录、
 * mock 掉 ES 即可覆盖全部处置分支，不需要起真实 Elasticsearch。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FallbackReplayJobTest {

    @TempDir
    Path fallbackDir;

    @Mock
    private EsLogRepository esLogRepository;

    private TraceMindProperties properties;
    private FallbackReplayJob job;

    @BeforeEach
    void setUp() {
        properties = new TraceMindProperties();
        properties.getFallback().setDir(fallbackDir.toString());
        properties.getFallback().setMaxReplayAttempts(3);
        job = new FallbackReplayJob(esLogRepository, properties, new ObjectMapper());
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private LogEntryDoc doc(String id, String rawText) {
        LogEntryDoc d = new LogEntryDoc();
        d.setId(id);
        d.setProjectId("p-001");
        d.setTimestamp("2026-10-22T08:00:00Z");
        d.setLevel("ERROR");
        d.setRawText(rawText);
        d.setFilePath("C:/logs/app.log");
        d.setLineNumber(1);
        return d;
    }

    /** 按生产者的格式写一个兜底文件 */
    private Path writeFallback(List<LogEntryDoc> docs) throws IOException {
        return writeFallbackRaw(docs.stream().map(this::toJson).toList());
    }

    private Path writeFallbackRaw(List<String> lines) throws IOException {
        Path file = fallbackDir.resolve("fallback-t-001-20261022-160000-1.jsonl");
        StringBuilder sb = new StringBuilder();
        lines.forEach(l -> sb.append(l).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private String toJson(LogEntryDoc d) {
        try {
            return new ObjectMapper().writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<LogEntryDoc> submittedOnce() {
        ArgumentCaptor<List<LogEntryDoc>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(esLogRepository).bulkInsert(captor.capture());
        return captor.getValue();
    }

    // ==================================================================
    // A1：部分失败绝不能删文件
    // ==================================================================

    @Test
    @DisplayName("★ A1 核心：部分失败时文件不被删除，内容收敛为剩下的失败条目")
    void keepsFailedDocsWhenBulkPartiallyFails() throws IOException {
        LogEntryDoc a = doc("p-001:C:/logs/app.log:1", "line one");
        LogEntryDoc b = doc("p-001:C:/logs/app.log:2", "line two rejected");
        LogEntryDoc c = doc("p-001:C:/logs/app.log:3", "line three");
        writeFallback(List.of(a, b, c));

        // ES 拒了中间那条：HTTP 200 + errors=true，不抛异常
        when(esLogRepository.bulkInsert(anyList()))
                .thenReturn(new BulkResult(List.of(a, c),
                        List.of(new BulkResult.Failed(b, "mapper_parsing_exception"))));

        job.replay();

        // ★ 按语义查而非写死文件名：部分失败后作业会重命名文件以累加重试次数，
        //   关键是"数据还在、且收敛到只剩失败的那条"，叫什么名字属于实现细节。
        List<Path> survivors = listPendingFiles();
        assertThat(survivors).as("部分失败时数据必须留在磁盘上（绝不能删）").hasSize(1);

        List<String> remaining = Files.readAllLines(survivors.get(0), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        assertThat(remaining).as("文件应收敛到只剩真正失败的那一条")
                .hasSize(1)
                .allSatisfy(l -> assertThat(l).contains("line two rejected"));

        // 已成功的那条不该再出现在任何后续提交里
        assertThat(submittedOnce()).hasSize(3);
    }

    /** 主目录下待重放的兜底文件（排除 dead/ corrupt/ 子目录） */
    private List<Path> listPendingFiles() throws IOException {
        try (var s = Files.list(fallbackDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        }
    }

    @Test
    @DisplayName("全部成功才删除文件")
    void deletesFileOnlyWhenAllSucceeded() throws IOException {
        LogEntryDoc a = doc("p-001:C:/logs/app.log:1", "only line");
        Path file = writeFallback(List.of(a));

        when(esLogRepository.bulkInsert(anyList())).thenReturn(BulkResult.allOk(List.of(a)));

        job.replay();

        assertThat(file).as("全成功后文件应被回收").doesNotExist();
    }

    @Test
    @DisplayName("整批抛异常（ES 连不上）：文件原样保留并累计一次重试")
    void keepsFileAndCountsAttemptOnWholeBatchFailure() throws IOException {
        LogEntryDoc a = doc("p-001:C:/logs/app.log:1", "line one");
        Path file = writeFallback(List.of(a));

        when(esLogRepository.bulkInsert(anyList()))
                .thenThrow(new RuntimeException("connection refused"));

        job.replay();

        try (var s = Files.list(fallbackDir)) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertThat(names).anyMatch(n -> n.contains(".attempt1."));
        }
    }

    @Test
    @DisplayName("★ 毒消息超上限后移入 dead/ 归档，不再无限陪跑")
    void quarantinesPoisonFileAfterMaxAttempts() throws IOException {
        LogEntryDoc poison = doc("p-001:C:/logs/app.log:9", "never writable");
        // 文件名带上已达上限的次数（maxReplayAttempts=3 → attempt3 即触发）
        Path file = fallbackDir.resolve("fallback-t-001-20261022-160000-1.attempt3.jsonl");
        Files.writeString(file, toJson(poison) + "\n", StandardCharsets.UTF_8);

        when(esLogRepository.bulkInsert(anyList()))
                .thenReturn(new BulkResult(List.of(),
                        List.of(new BulkResult.Failed(poison, "mapper_parsing_exception"))));

        job.replay();

        assertThat(file).as("已达上限的文件不该留在主目录反复重试").doesNotExist();
        assertThat(fallbackDir.resolve("dead")).as("应移入 dead/ 保留现场").exists();
        try (var s = Files.list(fallbackDir.resolve("dead"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList())
                    .anyMatch(n -> n.startsWith("fallback-t-001"));
        }
    }

    // ==================================================================
    // A2：坏行不能阻塞好行
    // ==================================================================

    @Test
    @DisplayName("★ A2 核心：文件含一行坏 JSON 时，正常行仍被回灌，坏行隔离进 corrupt/")
    void isolatesCorruptLineWithoutBlockingGoodOnes() throws IOException {
        LogEntryDoc good = doc("p-001:C:/logs/app.log:1", "good line");
        // 模拟进程崩溃时写了一半的一行
        Path file = writeFallbackRaw(List.of(toJson(good), "{\"id\":\"p-001:trunca"));

        when(esLogRepository.bulkInsert(anyList())).thenReturn(BulkResult.allOk(List.of(good)));

        job.replay();

        // ★ 关键断言：好行确实被提交了，而不是整文件放弃
        assertThat(submittedOnce())
                .as("坏行不得阻塞同文件的正常数据")
                .extracting(LogEntryDoc::getRawText)
                .containsExactly("good line");

        assertThat(fallbackDir.resolve("corrupt")).as("坏行应留档供人工排查").exists();
    }

    @Test
    @DisplayName("全是空行的文件直接清理，不发无谓请求")
    void removesEmptyFileWithoutCallingEs() throws IOException {
        Path file = writeFallbackRaw(List.of("", "  ", ""));

        job.replay();

        assertThat(file).doesNotExist();
        org.mockito.Mockito.verify(esLogRepository, org.mockito.Mockito.never()).bulkInsert(anyList());
    }

    @Test
    @DisplayName("目录不存在时静默返回 —— ES 健康时根本不会有这些文件")
    void returnsQuietlyWhenNoFallbackDir() {
        properties.getFallback().setDir(fallbackDir.resolve("not-created").toString());

        job.replay();   // 不应抛异常

        org.mockito.Mockito.verifyNoInteractions(esLogRepository);
    }
}
