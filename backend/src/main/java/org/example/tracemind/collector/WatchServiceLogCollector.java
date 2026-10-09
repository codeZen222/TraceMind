package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.enums.LogLevel;
import org.example.tracemind.config.TraceMindProperties;
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

    private final StringRedisTemplate redis;
    private final EsLogRepository esLogRepository;
    private final PythonClient pythonClient;
    private final LogMonitorTaskMapper logMonitorTaskMapper;
    private final CollectorRegistry registry;
    private final TraceMindProperties properties;
    private final ObjectMapper objectMapper;
    private final ErrorRadarService errorRadarService;

    private volatile boolean running = true;

    /** 内存累加器：避免每批都写 MySQL */
    private long linesRead;
    private long errorsDetected;
    private long ruleMatched;
    private long agentTriggered;
    private long lastFlush = System.currentTimeMillis();
    private long lastMysqlSync = System.currentTimeMillis();

    public WatchServiceLogCollector(String taskId,
                                    String projectId,
                                    String filePath,
                                    StringRedisTemplate redis,
                                    EsLogRepository esLogRepository,
                                    PythonClient pythonClient,
                                    LogMonitorTaskMapper logMonitorTaskMapper,
                                    CollectorRegistry registry,
                                    TraceMindProperties properties,
                                    ObjectMapper objectMapper,
                                    ErrorRadarService errorRadarService) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.filePath = filePath;
        this.redis = redis;
        this.esLogRepository = esLogRepository;
        this.pythonClient = pythonClient;
        this.logMonitorTaskMapper = logMonitorTaskMapper;
        this.registry = registry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.errorRadarService = errorRadarService;
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
            registry.unregister(taskId);   // 从登记表摘掉，否则 stop 会拿到一个死线程
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
            long offset = offsetFromRedis();

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
                    redis.opsForValue().set("offset:" + taskId, String.valueOf(raf.getFilePointer()));
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

            redis.opsForValue().set("offset:" + taskId, String.valueOf(raf.getFilePointer()));
            redis.opsForValue().set("fingerprint:" + taskId, currentFingerprint);
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

    private String fingerprintFromRedis() {
        return redis.opsForValue().get("fingerprint:" + taskId);
    }

    private long offsetFromRedis() {
        String v = redis.opsForValue().get("offset:" + taskId);
        if (v != null) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                log.warn("Redis 里的 offset 不是数字，尝试 MySQL 兜底。taskId={} value={}", taskId, v);
            }
        }
        // Redis 没有 → 从 MySQL 兜底快照恢复
        LogMonitorTask task = logMonitorTaskMapper.selectById(taskId);
        if (task != null && task.getOffset() != null && task.getOffset() > 0) {
            log.warn("Redis offset 缺失，从 MySQL 兜底恢复 offset={} taskId={}", task.getOffset(), taskId);
            return task.getOffset();
        }
        return 0L;
    }

    // ==================================================================
    // 攒批落库
    // ==================================================================

    private void flush(List<String> lines) {
        List<PythonClient.ParseResult> parsed = pythonClient.parse(projectId, lines);

        String now = Instant.now().toString();
        List<LogEntryDoc> docs = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            LogEntryDoc doc = new LogEntryDoc();
            doc.setId(projectId + ":" + filePath + ":" + (linesRead + i + 1));
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

        boolean success = false;
        for (int retry = 0; retry < MAX_RETRY; retry++) {
            try {
                esLogRepository.bulkInsert(docs);
                success = true;
                break;
            } catch (Exception e) {
                log.warn("ES 写入失败，准备重试 {}/{} taskId={}", retry + 1, MAX_RETRY, taskId, e);
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

        if (!success) {
            writeToLocalFallback(docs);
            log.error("ES 写入 {} 次重试全部失败，已写入本地兜底文件 taskId={} count={}",
                    MAX_RETRY, taskId, docs.size());
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
            errorRadarService.scan(projectId, errorClusters);
        }
        // TODO(J6)：这里接诊断触发的钩子 —— 连续 N 个 ERROR 时调 /api/diagnosis/trigger
        //           现在 agentTriggered 恒为 0

        lastFlush = System.currentTimeMillis();
        log.debug("已采集 {} 行 taskId={}", linesRead, taskId);
    }

    // ==================================================================
    // 本地兜底写入
    // ==================================================================

    private static final AtomicLong FALLBACK_SEQ = new AtomicLong(0);

    private void writeToLocalFallback(List<LogEntryDoc> docs) {
        try {
            String fallbackDir = properties.getFallback().getDir();
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
                    writer.write(objectMapper.writeValueAsString(doc));
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
     * 回写 MySQL。
     *
     * <p>Redis 是 offset 的真身，这张表只是「重启后能知道大概读到哪了」的兜底。
     * offset 用 Redis 的实时值，不用内存里的。</p>
     */
    private void writeMysqlSnapshot(boolean running) {
        try {
            LogMonitorTask task = logMonitorTaskMapper.selectById(taskId);
            if (task == null) {
                return;
            }
            task.setRunning(running);
            task.setOffset(offsetFromRedis());
            task.setFileSize(Paths.get(filePath).toFile().length());
            task.setLastReadAt(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            task.setLinesRead(linesRead);
            task.setErrorsDetected((int) errorsDetected);
            task.setRuleMatched((int) ruleMatched);
            task.setAgentTriggered((int) agentTriggered);
            task.setDegraded(pythonClient.isDegraded());
            logMonitorTaskMapper.updateStatus(task);
            lastMysqlSync = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("回写 log_monitor_task 失败 taskId={} 原因={}", taskId, e.getMessage());
        }
    }
}