package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.RedisKeys;
import org.example.tracemind.common.enums.LogLevel;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsDocId;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.LogMonitorTaskMapper;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.entity.LogMonitorTask;
import org.example.tracemind.service.ErrorRadarService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时日志采集线程：一个实例对应一个监听任务。
 *
 * <p>它干的事：WatchService 盯住日志文件所在目录 → 文件一有变化就读新增的行
 * → 攒批调 Python 换模板 → bulk 写 ES → 把新位置写回 Redis。</p>
 *
 * <p>三个必须处理的边界：<b>断点续读</b>（offset 存 Redis）、
 * <b>文件轮转</b>（file.length() &lt; offset 时归零）、
 * <b>防抖</b>（同文件 300ms 内的多次事件合并）。</p>
 */
@Slf4j
public class WatchServiceLogCollector implements Runnable {

    /** 攒批大小 */
    private static final int BATCH_SIZE = 50;
    /** 攒批时间：距上次 flush 超过 500ms 就发，不管够没够 50 行 */
    private static final long FLUSH_INTERVAL_MS = 500;
    /** 每 30 秒把统计回写一次 MySQL（Redis 是主、MySQL 是兜底快照） */
    private static final long MYSQL_SYNC_INTERVAL_MS = 30_000;
    /** 同文件多次修改事件的防抖窗口 */
    private static final long DEBOUNCE_MS = 300;
    /** ES 写入失败最大重试次数 */
    private static final int MAX_RETRY = 3;
    /** 单轮最大处理行数：防止日志暴涨时一次读完卡死 */
    private static final int MAX_LINES_PER_ROUND = 5000;
    /** 单轮最大耗时（ms）：超时就让出主循环，检查 running 和 syncMysql */
    private static final long MAX_ROUND_TIME_MS = 10_000;

    private final String taskId;
    private final String projectId;
    private final String filePath;

    /** 全部容器 Bean 的打包对象，见 {@link CollectorDependencies} */
    private final CollectorDependencies deps;

    private volatile boolean running = true;

    /**
     * 内存累加器：避免每批都写 MySQL。
     *
     * <p>⚠️ 初值必须从 {@link CollectorOffsetStore} 恢复（由 {@link #init()} 在启动前完成），
     * 不能默认 0 —— 它决定 ES 文档 ID 里的行号部分，归零会让新行覆盖旧行。
     * 每次 flush 后随 offset 一起写回 store（同一个 Hash，原子推进）。</p>
     */
    private long linesRead;
    private long errorsDetected;
    private long ruleMatched;
    private long agentTriggered;
    private long lastFlush = System.currentTimeMillis();
    private long lastMysqlSync = System.currentTimeMillis();

    public WatchServiceLogCollector(String taskId,
                                    String projectId,
                                    String filePath,
                                    CollectorDependencies deps) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.filePath = filePath;
        this.deps = deps;
        // ★ 构造器里不做任何 I/O：位点恢复交给 init()，由工厂/调用方在启动线程前显式调用。
        //   此前这里直接 read Redis+MySQL，导致测试无法 new 出一个"干净初始态"的对象
        //   （必须先 stub 好 Redis 才能构造），也无法 mock 一个 collector 而不触碰外部系统。
    }

    /**
     * 恢复续读状态。必须在把实例交给线程池<b>之前</b>调用一次。
     *
     * <p>之所以不放在构造器也不放在 {@code run()} 里：放构造器会引入构造期 I/O；
     * 放 {@code run()} 则"已构造但未初始化"的状态可能被误用（比如 stop→start 复用时漏调）。
     * 显式一个方法、由工厂统一负责调用，时序最清楚。</p>
     */
    public void init() {
        this.linesRead = deps.getOffsetStore().read(taskId).lines();
    }

    /** 停止信号。线程会在当前这一轮跑完后退出 */
    public void stop() {
        this.running = false;
        log.info("收到停止信号 taskId={}", taskId);
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    @Override
    public void run() {
        Path path = Paths.get(filePath);
        Path dir = path.getParent();
        String fileName = path.getFileName().toString();

        // ★ 启动 WatchService
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // ★ 注册目录监听：ENTRY_MODIFY 事件
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            log.info("采集线程启动 taskId={} file={}", taskId, filePath);

            while (running) {
                // ★ 监听事件：最多等待 500ms
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key != null) {
                    // ★ 检测到修改事件
                    boolean modified = key.pollEvents().stream()
                            .anyMatch(e -> e.context().toString().endsWith(fileName));
                    // ★ 重置 key 以便继续监听
                    key.reset();
                    // ★ 有事件时才读，避免漏掉启动前的存量
                    if (modified) {
                        Thread.sleep(DEBOUNCE_MS);   // 防抖
                    }
                }
                // ★ 无条件读一次：文件在 start 之前就有的内容不会触发 ENTRY_MODIFY，
                //   只在"有事件时才读"会漏掉启动前的存量
                readNewLines();
                syncMysqlIfDue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("采集线程被中断 taskId={}", taskId);
        } catch (Exception e) {
            log.error("采集线程异常退出 taskId={}", taskId, e);
        } finally {
            // ★ 两件收尾的事，缺一不可
            deps.getRegistry().unregister(taskId);   // 从登记表摘掉，否则 stop 会拿到一个死线程
            writeMysqlSnapshot(false);     // 最后一次快照，running=false
            log.info("采集线程已退出 taskId={}，累计 {} 行 / {} 个 ERROR",
                    taskId, linesRead, errorsDetected);
        }
    }

    // ==================================================================
    // 读新增内容
    // ==================================================================

    private void readNewLines() {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            long offset = deps.getOffsetStore().read(taskId).offset();

            // ★ 文件身份校验：创建时间 + 首行哈希
            String currentFingerprint = computeFingerprint(raf);
            String savedFingerprint = fingerprintFromRedis();
            if (savedFingerprint != null && !savedFingerprint.equals(currentFingerprint)) {
                log.warn("检测到文件被替换（指纹不一致），offset 归零重读。taskId={} old={} new={}",
                        taskId, savedFingerprint, currentFingerprint);
                offset = 0;
            }

            // ★ 大小检测：copytruncate 场景（文件被清空后重写）
            if (raf.length() < offset) {
                log.warn("检测到文件轮转（长度缩小），offset 归零重读。taskId={} oldOffset={} newLength={}",
                        taskId, offset, raf.length());
                offset = 0;
            }

            raf.seek(offset);

            long roundStart = System.currentTimeMillis();
            int linesThisRound = 0;
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            String line;

            while ((line = raf.readLine()) != null) {
                if (!running) {
                    log.info("收到停止信号，中断当前读取。taskId={} linesThisRound={}", taskId, linesThisRound);
                    break;
                }

                batch.add(new String(line.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8));
                linesThisRound++;

                boolean full = batch.size() >= BATCH_SIZE;
                boolean due = System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS;
                if (full || due) {
                    flush(batch);
                    batch.clear();
                    savePosition(raf);
                }

                if (linesThisRound >= MAX_LINES_PER_ROUND
                        || System.currentTimeMillis() - roundStart >= MAX_ROUND_TIME_MS) {
                    log.debug("单轮预算用完，让出主循环。taskId={} lines={} elapsed={}ms",
                            taskId, linesThisRound, System.currentTimeMillis() - roundStart);
                    break;
                }
            }

            if (!batch.isEmpty()) {
                flush(batch);
                batch.clear();
            }

            savePosition(raf);
            saveFingerprint(currentFingerprint);
        } catch (FileNotFoundException e) {
            log.warn("日志文件不存在，跳过本轮。taskId={} path={}", taskId, filePath);
        } catch (IOException e) {
            log.error("读取新增内容失败 taskId={}", taskId, e);
        }
    }

    /**
     * 计算文件指纹：创建时间 + 首行内容哈希。
     *
     * <p>为什么用这两个：
     * - 创建时间：文件被删重建后一定变（精确到毫秒）
     * - 首行哈希：兜底，某些文件系统创建时间不可靠（如 NFS），
     *   但只要内容不同就能发现</p>
     */
    private String computeFingerprint(RandomAccessFile raf) throws IOException {
        long createTime = 0;
        try {
            createTime = Files.readAttributes(Paths.get(filePath), BasicFileAttributes.class)
                    .creationTime().toMillis();
        } catch (IOException e) {
            log.debug("无法获取文件创建时间，仅用首行哈希。path={}", filePath);
        }

        long savedPos = raf.getFilePointer();
        raf.seek(0);
        String firstLine = raf.readLine();
        raf.seek(savedPos);

        int hash = firstLine == null ? 0 : firstLine.hashCode();
        return createTime + ":" + hash;
    }

    /**
     * 把当前读取位置和累计行数一起写回 store。
     *
     * <p>必须在 flush 之后调用——flush 会推进 {@link #linesRead}，
     * 两个值同源同时落盘，才不会出现「offset 到了第 5000 行、linesRead 还是 0」这种半恢复。</p>
     */
    private void savePosition(RandomAccessFile raf) throws IOException {
        deps.getOffsetStore().write(taskId, raf.getFilePointer(), linesRead);
    }

    /**
     * 指纹只用于判断「文件被替换」，丢了最多多读一轮，不是关键路径。
     * 所以 Redis 挂了静默跳过，绝不让采集线程因为一个辅助 key 写失败而退出。
     */
    private void saveFingerprint(String fingerprint) {
        try {
            deps.getRedis().opsForValue().set(RedisKeys.collectorFingerprint(taskId), fingerprint);
        } catch (Exception e) {
            log.warn("写文件指纹失败，本轮跳过（不影响读取）。taskId={} 原因={}", taskId, e.getMessage());
        }
    }

    private String fingerprintFromRedis() {
        try {
            return deps.getRedis().opsForValue().get(RedisKeys.collectorFingerprint(taskId));
        } catch (Exception e) {
            log.warn("读文件指纹失败，按无指纹处理。taskId={} 原因={}", taskId, e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 攒批落库
    // ==================================================================

    private void flush(List<String> lines) {
        List<PythonClient.ParseResult> parsed = deps.getPythonClient().parse(projectId, lines);

        String now = Instant.now().toString();
        List<LogEntryDoc> docs = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            LogEntryDoc doc = new LogEntryDoc();
            // ★ ID 一律走 EsDocId（内部会把 filePath 归一成裸文件名，与导入链路同形）。
            //   而 filePath 字段本身保留绝对路径 —— 它是"数据从哪来"的信息，不参与主键。
            doc.setId(EsDocId.of(projectId, filePath, (int) (linesRead + i + 1)));
            doc.setProjectId(projectId);
            doc.setTimestamp(now);
            doc.setRawText(raw);
            LogLevel level = LogLevel.parseFromLine(raw);
            doc.setLevel(level == null ? LogLevel.INFO.name() : level.name());
            doc.setFilePath(filePath);
            doc.setLineNumber((int) (linesRead + i + 1));
            if (i < parsed.size()) {
                doc.setTemplate(parsed.get(i).template());
                doc.setClusterId(parsed.get(i).clusterId());
            }
            docs.add(doc);
        }

        /*
         * ★ 只重试、只落盘「还没成功的那部分」。
         *
         *   原先用 boolean success 表示整批结果，有两个后果：
         *   ① 部分失败时 bulkInsert 不抛 → success=true → 失败的条目既不进 ES 也不进兜底文件，静默消失；
         *   ② 整批重试会把已写成功的条目重复投 2 遍（ES 文档 ID 幂等所以不错数据，但纯浪费）。
         *   现在 pending 逐轮收敛到只剩真失败的，最终落盘的也是这个子集。
         */
        List<LogEntryDoc> pending = docs;
        for (int retry = 0; retry < MAX_RETRY && !pending.isEmpty(); retry++) {
            try {
                BulkResult result = deps.getEsLogRepository().bulkInsert(pending);
                pending = result.failedDocs();
            } catch (Exception e) {
                log.warn("ES 写入失败，准备重试 {}/{} taskId={} 待写={}",
                        retry + 1, MAX_RETRY, taskId, pending.size(), e);
                if (retry < MAX_RETRY - 1) {
                    try {
                        Thread.sleep(1000L * (retry + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!pending.isEmpty()) {
            enqueueOrFallbackToFile(pending);
            log.error("ES 写入 {} 次重试后仍有 {} 条未成功（本批共 {} 条），已进入兜底（Stream 优先，Redis 挂时退回文件） taskId={}",
                    MAX_RETRY, pending.size(), docs.size(), taskId);
        }

        linesRead += docs.size();
        // ★ 新错误雷达：顺带收集本批 ERROR 的 clusterId，喂雷达判新
        Set<String> errorClusters = new HashSet<>();
        for (LogEntryDoc doc : docs) {
            if (LogLevel.ERROR.name().equals(doc.getLevel())) {
                errorsDetected++;
                if (doc.getClusterId() != null) {
                    errorClusters.add(doc.getClusterId());
                }
            }
        }
        if (!errorClusters.isEmpty()) {
            deps.getErrorRadarService().scan(projectId, errorClusters);
        }
        // TODO(J6)：这里接诊断触发的钩子 —— 连续 N 个 ERROR 时调 /api/diagnosis/trigger
        //           现在 agentTriggered 恒为 0

        lastFlush = System.currentTimeMillis();
        log.debug("已采集 {} 行 taskId={}", linesRead, taskId);
    }

    // ==================================================================
    // 兜底写入（Stream 主路径 + 文件退回）
    // ==================================================================

    private static final AtomicLong FALLBACK_SEQ = new AtomicLong(0);

    /**
     * 兜底写入的统一入口：<b>逐条</b>先入 Stream，Redis 不可用时退回本地文件。
     *
     * <p>为什么逐条：Stream 的 ack 是消息级的（一条日志 = 一个 entry），
     * 逐条入队才能保证消费端能对成功/失败条目分别 ack，不重蹈「整批塞一条」的覆辙。</p>
     *
     * <p>为什么退回文件而不是丢：Redis 挂时若只 log.error，这几条日志就静默消失了。
     * 退回 {@code writeToLocalFallback} 后，它们走既有 {@code FallbackReplayJob} 文件链，
     * 至少不丢数据。文件方案是「最后防线」，Stream 是「主路径」。</p>
     */
    private void enqueueOrFallbackToFile(List<LogEntryDoc> docs) {
        // 收集 Stream 也写不进的条目，统一退回文件（避免每失败一条就开一次文件）
        List<LogEntryDoc> fileFallback = new ArrayList<>();
        for (LogEntryDoc doc : docs) {
            try {
                deps.getEsFallbackStream().enqueue(doc);
            } catch (Exception e) {
                log.warn("兜底流入队失败（Redis 不可用），退回文件 taskId={} docId={} 原因={}",
                        taskId, doc.getId(), e.getMessage());
                fileFallback.add(doc);
            }
        }
        if (!fileFallback.isEmpty()) {
            writeToLocalFallback(fileFallback);
        }
    }

    private void writeToLocalFallback(List<LogEntryDoc> docs) {
        try {
            String fallbackDir = deps.getProperties().getFallback().getDir();
            Path dirPath = Paths.get(fallbackDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            String fileName = "fallback-" + taskId.replaceAll("[^a-zA-Z0-9_-]", "_")
                    + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                    + "-" + FALLBACK_SEQ.incrementAndGet() + ".jsonl";
            Path filePath = dirPath.resolve(fileName);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                for (LogEntryDoc doc : docs) {
                    writer.write(deps.getObjectMapper().writeValueAsString(doc));
                    writer.newLine();
                }
            }
            log.info("兜底文件已写入 path={} count={}", filePath, docs.size());
        } catch (Exception e) {
            log.error("写兜底文件也失败了 taskId={} count={}", taskId, docs.size(), e);
        }
    }

    // ==================================================================
    // MySQL 兜底快照
    // ==================================================================

    private void syncMysqlIfDue() {
        if (System.currentTimeMillis() - lastMysqlSync >= MYSQL_SYNC_INTERVAL_MS) {
            writeMysqlSnapshot(true);
        }
    }

    /**
     * 回写 MySQL 兜底快照。
     *
     * <p>Redis 是位点的真身，这张表只是「Redis 空了也能知道大概读到哪了」的兜底，
     * 所以 offset / linesRead 都取 store 的实时值，不用内存里的——
     * 内存值在 Redis 故障期间可能已经领先或落后于持久化值，以 store 为准两边才对得上。</p>
     */
    private void writeMysqlSnapshot(boolean running) {
        try {
            LogMonitorTask task = deps.getLogMonitorTaskMapper().selectById(taskId);
            if (task == null) {
                return;
            }
            CollectorOffsetStore.Position pos = deps.getOffsetStore().read(taskId);
            task.setRunning(running);
            task.setOffset(pos.offset());
            task.setFileSize(Paths.get(filePath).toFile().length());
            task.setLastReadAt(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            task.setLinesRead(pos.lines());
            task.setErrorsDetected((int) errorsDetected);
            task.setRuleMatched((int) ruleMatched);
            task.setAgentTriggered((int) agentTriggered);
            task.setDegraded(deps.getPythonClient().isDegraded());
            deps.getLogMonitorTaskMapper().updateStatus(task);
            lastMysqlSync = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("回写 log_monitor_task 失败 taskId={} 原因={}", taskId, e.getMessage());
        }
    }
}