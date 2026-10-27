package org.example.tracemind.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.enums.LogLevel;
import org.example.tracemind.config.AsyncConfig;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsDocId;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.ImportTaskMapper;
import org.example.tracemind.pojo.dto.LogSource;
import org.example.tracemind.pojo.entity.ImportTask;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.vo.ImportProgressVO;
import org.example.tracemind.service.ErrorRadarService;
import org.example.tracemind.service.ImportService;
import org.example.tracemind.service.OwnershipGuard;
import org.example.tracemind.service.UploadStaging;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ImportServiceImpl implements ImportService {

    private final ImportTaskMapper importTaskMapper;
    private final OwnershipGuard ownershipGuard;
    private final EsLogRepository esLogRepository;
    private final PythonClient pythonClient;
    private final ErrorRadarService errorRadarService;
    private final UploadStaging uploadStaging;
    /** ★ 依赖 {@link Executor} 抽象而非具体的 ThreadPoolTaskExecutor，与 DiagnosisServiceImpl 对齐 */
    private final Executor importExecutor;

    public ImportServiceImpl(ImportTaskMapper importTaskMapper,
                             OwnershipGuard ownershipGuard,
                             EsLogRepository esLogRepository,
                             PythonClient pythonClient,
                             ErrorRadarService errorRadarService,
                             UploadStaging uploadStaging,
                             @Qualifier(AsyncConfig.IMPORT_EXECUTOR) Executor importExecutor) {
        // ★ 手写构造器而不是 @RequiredArgsConstructor：Lombok 默认不会把字段上的 @Qualifier
        //   复制到构造参数（见 README 常见坑 / DiagnosisServiceImpl 的同款注释）。
        //   目前字段名与 bean 名恰好都是 importExecutor、靠名字回退能对上，但那是巧合 ——
        //   一旦有人改字段名就会静默注入错线程池（导入任务跑去和诊断抢线程）。
        this.importTaskMapper = importTaskMapper;
        this.ownershipGuard = ownershipGuard;
        this.esLogRepository = esLogRepository;
        this.pythonClient = pythonClient;
        this.errorRadarService = errorRadarService;
        this.uploadStaging = uploadStaging;
        this.importExecutor = importExecutor;
    }

    private final ConcurrentMap<String, Integer> progressCache = new ConcurrentHashMap<>();

    /** 日志行首的时间戳：{@code 2026-10-05 17:05:02.118} / {@code 2026-10-05T17:05:02,118} 两种写法都认 */
    private static final Pattern LOG_TIME =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(?:[.,](\\d{1,3}))?");

    /**
     * 从日志行首解析出<b>日志自己</b>的发生时间，解析不到返回 null。
     *
     * <p>为什么非要解析：导入的是<b>历史</b>日志，拿 {@code Instant.now()} 当 timestamp 是错的 ——
     * 一条 17:05 的日志会被记成"导入时刻"，前端把相对时间显示成"刚刚"，
     * 而同一次导入的所有行时间戳几乎相同，按小时聚合的趋势图就只剩一个点（画出来是空的）。</p>
     *
     * <p>只认行<b>开头</b>的时间戳，<b>不做全文模糊匹配</b>：从正文里随便捞一个像时间的串，
     * 会造出比"时间不准"更难查的错数据。</p>
     *
     * <p>日志里的时间是本机时区的墙上时间，ES 存的是绝对时刻，所以这里补上系统时区再转 UTC。</p>
     */
    private static String timestampOf(String rawLine) {
        Matcher m = LOG_TIME.matcher(rawLine);
        if (!m.find()) {
            return null;
        }
        // 毫秒补齐到 3 位：".1" 是 100ms，不是 1ms
        String millis = m.group(3) == null ? "000" : (m.group(3) + "000").substring(0, 3);
        try {
            LocalDateTime local = LocalDateTime.parse(m.group(1) + "T" + m.group(2) + "." + millis);
            return local.atZone(ZoneId.systemDefault()).toInstant().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 启动一次导入。
     *
     * <p>★ 入参是 {@link LogSource}（已落盘的普通文件路径）而不是 {@code MultipartFile} ——
     * 本方法会把任务交给线程池异步执行，而 HTTP 请求在返回 taskId 时就结束了；
     * {@code MultipartFile} 只是容器临时文件的句柄，请求一结束就被清理，
     * 异步线程再去读就会拿到失效句柄（间歇性「文件读取失败」的根因）。
     * 所以接管动作由 controller 在请求线程内同步完成，见 {@code UploadStaging}。</p>
     */
    @Override
    public String startImport(String projectId, List<LogSource> sources) {
        // ★ 本方法内的清理责任边界：只要还没成功提交异步任务，异常路径就必须自己清暂存；
        //   一旦 execute() 成功，文件就交给 runImport 的 finally 负责，这里绝不能重复清（会把正在读的文件删掉）。
        try {
            // ★ 多租户：项目归属校验
            ownershipGuard.requireOwned(projectId);
            if (sources == null || sources.isEmpty()) {
                throw new BizException("请选择要导入的日志文件");
            }
            ImportTask running = importTaskMapper.findRunningByProjectId(projectId);
            if (running != null) {
                throw new BizException("该项目已有导入任务在执行: " + running.getTaskId());
            }

            String taskId = "imp-" + UUID.randomUUID().toString().substring(0, 8);
            int totalLines = estimateLines(sources);

            ImportTask task = new ImportTask();
            task.setTaskId(taskId);
            task.setProjectId(projectId);
            task.setTotal(totalLines);
            task.setProcessed(0);
            task.setStatus("RUNNING");
            importTaskMapper.insert(task);

            importExecutor.execute(() -> runImport(taskId, projectId, sources));
            return taskId;
        } catch (RuntimeException e) {
            // 走到这里说明异步任务没起来（或根本没建到），没人会替我们清
            uploadStaging.cleanup(projectId);
            throw e;
        }
    }

    @Override
    public ImportProgressVO progress(String taskId) {
        ImportTask task = importTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        // ★ 多租户：查进度也校验归属（否则能探测别人项目的导入任务）
        ownershipGuard.requireOwned(task.getProjectId());
        ImportProgressVO vo = new ImportProgressVO();
        vo.setTotal(task.getTotal());
        vo.setProcessed(task.getProcessed());
        vo.setStatus(task.getStatus());
        return vo;
    }

    private void runImport(String taskId, String projectId, List<LogSource> sources) {
        int processed = 0;
        try {
            for (LogSource source : sources) {
                // 每个文件的行号都从 1 重新数（见 importOneFile），这里只累加总进度
                processed += importOneFile(projectId, source);
                importTaskMapper.updateProgress(taskId, processed, "RUNNING");
                progressCache.put(taskId, processed);
            }
            importTaskMapper.updateProgress(taskId, processed, "DONE");
            progressCache.remove(taskId);
            log.info("导入完成 taskId={}, 共 {} 行", taskId, processed);
        } catch (Exception e) {
            log.error("导入失败 taskId={}，已写入的 ES 数据保留", taskId, e);
            importTaskMapper.updateProgress(taskId, processed, "FAILED");
            progressCache.remove(taskId);
        } finally {
            // ★ 接管之后清理责任在我们手上（容器不再管这些文件）。
            //   放 finally 而不是正常流程末尾：失败路径同样要清，否则每次中断都留下几百 MB。
            //   键是 projectId —— 暂存目录由 controller 按项目分组（同项目禁止并发导入）。
            uploadStaging.cleanup(projectId);
        }
    }

    private int importOneFile(String projectId, LogSource source) {
        List<String> batch = new ArrayList<>(500);
        /*
         * startLineNo 是"本文件已写入多少行"的 0 基游标（本文件第 1 行的值是 0），
         * 真正对外报的行号在 flushBatch 里 +1，所以本文件第一行报的是 1。
         *
         * ⚠ 这里<b>必须从 0 开始</b>，不能拿全局累计值：
         *   ES 文档 ID 是 projectId:文件名:行号，这里的行号是"文件内"的行号。
         *   之前传的 alreadyProcessed 是跨文件累计的，会让第 2 个文件的第一行接着
         *   第 1 个文件往下数 —— 同一个文件单独导一次、跟别人一起导一次，行号还不一样。
         */
        /*
         * ★ 只累计「真正写进 ES」的行数，而不是提交了多少行。
         *
         *   原先 totalLines 在 flushBatch 之前就 ++，而 bulkInsert 对部分失败不抛异常 ——
         *   于是失败的行也被计入 processed，任务最终还标 DONE：
         *   用户看到「12500/12500 已完成」但实际少了数据，且没有任何地方能察觉。
         *   现在 flushBatch 返回成功条数，差额累加到 failedInFile 并让调用方如实报错。
         */
        int startLineNo = 0;
        int writtenLines = 0;
        int failedLines = 0;

        // ★ 读自己接管下来的普通文件，不再碰 MultipartFile 的句柄（那个可能已随请求结束失效）
        try (BufferedReader reader = Files.newBufferedReader(source.path(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(line);
                if (batch.size() >= 500) {
                    int ok = flushBatch(projectId, source.displayName(), startLineNo, batch);
                    writtenLines += ok;
                    failedLines += batch.size() - ok;
                    startLineNo += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                int ok = flushBatch(projectId, source.displayName(), startLineNo, batch);
                writtenLines += ok;
                failedLines += batch.size() - ok;
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.error("读取导入文件失败 file={} path={}", source.displayName(), source.path(), e);
            throw BizException.serviceUnavailable("文件读取失败: " + source.displayName());
        }
        if (failedLines > 0) {
            log.error("导入文件存在未写入的日志 file={} 成功={} 失败={}（失败原因见上方 bulk 明细日志）",
                    source.displayName(), writtenLines, failedLines);
        }
        return writtenLines;
    }

    /**
     * 写一批导入日志。
     *
     * @return <b>确实写入 ES 的行数</b>（不是提交行数）——调用方用它累加进度，避免谎报完成
     */
    private int flushBatch(String projectId, String fileName, int startLineNo, List<String> lines) {
        List<PythonClient.ParseResult> parsed = pythonClient.parse(projectId, lines);

        List<LogEntryDoc> docs = new ArrayList<>(lines.size());
        /*
         * 时间戳逐行取日志自己的时间。取不到的（异常堆栈的后续行、没有时间戳的裸文本）
         * 沿用【上一行】的时间 —— 堆栈帧本来就属于上面那条日志，给它塞 now() 会把顺序打乱。
         * 整个批次都没时间戳时，才退回导入时刻。
         */
        String lastTs = Instant.now().toString();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String parsedTs = timestampOf(raw);
            if (parsedTs != null) {
                lastTs = parsedTs;
            }
            LogEntryDoc doc = new LogEntryDoc();

            /*
             * 行号统一用 1 基，且 ID 一律走 EsDocId —— 与采集链路同一个真源。
             *
             * 历史上这里出过两次事故，根因都是"两条链路各自手写同一套规则"：
             *   ① 行号基准：导入曾是 0 基、采集一直是 1 基，同一行给出 :509 / :510 两个 ID；
             *   ② 中间段：采集用绝对路径、导入用裸文件名，同一物理文件产生两套互不相干的文档。
             * 现在两条链路都调 EsDocId.of(...)，规则只有一份，改一处必然两处同步。
             */
            int lineNo = startLineNo + i + 1;

            doc.setId(EsDocId.of(projectId, fileName, lineNo));
            doc.setProjectId(projectId);
            doc.setTimestamp(lastTs);
            doc.setRawText(raw);
            LogLevel level = LogLevel.parseFromLine(raw);
            doc.setLevel(level == null ? LogLevel.INFO.name() : level.name());
            doc.setFilePath(fileName);
            doc.setLineNumber(lineNo);
            if (i < parsed.size()) {
                doc.setTemplate(parsed.get(i).template());
                doc.setClusterId(parsed.get(i).clusterId());
            }
            docs.add(doc);
        }

        /*
         * 导入是用户主动的一次性操作，失败重传成本很低（文件还在用户手上），
         * 所以这里<b>刻意不做本地兜底</b> —— 落盘反而会让用户看到"导入完成"但数据不全。
         * 正确处置是如实报错、精确计数，让上层把差额暴露出来。
         * （采集侧相反：那是持续被动接收，丢了就真没了，必须落 fallback。）
         *
         * ★ 返回真正写进 ES 的行数，调用方据此累加进度；不能返回提交数。
         */
        List<LogEntryDoc> pending = docs;
        for (int retry = 0; retry < 3 && !pending.isEmpty(); retry++) {
            try {
                BulkResult result = esLogRepository.bulkInsert(pending);
                pending = result.failedDocs();
            } catch (Exception e) {
                log.warn("ES 写入失败，重试 {}/{} 待写={}", retry + 1, 3, pending.size(), e);
                if (retry < 2) {
                    try {
                        Thread.sleep(1000L * (retry + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (pending.size() == docs.size()) {
            // 一条都没写进去（整批故障或重试全抛）→ 明确失败，交给上层中断该文件
            throw BizException.serviceUnavailable("ES 写入失败，重试 3 次仍不可用");
        }
        int written = docs.size() - pending.size();
        if (!pending.isEmpty()) {
            log.error("本批部分日志未写入 ES 成功={} 失败={}（导入不落盘，请检查后重新导入该文件）",
                    written, pending.size());
        }
        // ★ 新错误雷达：只喂「确实写进了 ES」的 ERROR，否则判新结果会引用查不到的日志
        Set<String> errorClusters = docs.stream()
                .filter(d -> LogLevel.ERROR.name().equals(d.getLevel()))
                .map(LogEntryDoc::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!errorClusters.isEmpty()) {
            errorRadarService.scan(projectId, errorClusters);
        }
        return written;
    }

    /** 用字节数估算总行数（进度条分母）。⚠️ 是估算值，见 README 已知问题。 */
    private int estimateLines(List<LogSource> sources) {
        long bytes = 0;
        for (LogSource s : sources) {
            bytes += s.size();
        }
        if (bytes <= 0) return 1;
        return (int) Math.max(1, bytes / 120);
    }
}