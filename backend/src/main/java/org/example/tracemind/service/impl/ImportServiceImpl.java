package org.example.tracemind.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.enums.LogLevel;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.ImportTaskMapper;
import org.example.tracemind.pojo.entity.ImportTask;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.example.tracemind.pojo.vo.ImportProgressVO;
import org.example.tracemind.service.ErrorRadarService;
import org.example.tracemind.service.OwnershipGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl {

    private final ImportTaskMapper importTaskMapper;
    private final OwnershipGuard ownershipGuard;
    private final EsLogRepository esLogRepository;
    private final PythonClient pythonClient;
    private final ErrorRadarService errorRadarService;
    @Qualifier("importExecutor")
    private final ThreadPoolTaskExecutor importExecutor;

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

    public String startImport(String projectId, MultipartFile[] files) {
        // ★ 多租户：项目归属校验
        ownershipGuard.requireOwned(projectId);
        if (files == null || files.length == 0) {
            throw new BizException("请选择要导入的日志文件");
        }
        ImportTask running = importTaskMapper.findRunningByProjectId(projectId);
        if (running != null) {
            throw new BizException("该项目已有导入任务在执行: " + running.getTaskId());
        }

        String taskId = "imp-" + UUID.randomUUID().toString().substring(0, 8);
        int totalLines = estimateLines(files);

        ImportTask task = new ImportTask();
        task.setTaskId(taskId);
        task.setProjectId(projectId);
        task.setTotal(totalLines);
        task.setProcessed(0);
        task.setStatus("RUNNING");
        importTaskMapper.insert(task);

        importExecutor.execute(() -> runImport(taskId, projectId, files));
        return taskId;
    }

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

    private void runImport(String taskId, String projectId, MultipartFile[] files) {
        int processed = 0;
        try {
            for (MultipartFile file : files) {
                // 每个文件的行号都从 1 重新数（见 importOneFile），这里只累加总进度
                processed += importOneFile(projectId, file);
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
        }
    }

    private int importOneFile(String projectId, MultipartFile file) {
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
        int startLineNo = 0;
        int totalLines = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(line);
                totalLines++;
                if (batch.size() >= 500) {
                    flushBatch(projectId, file.getOriginalFilename(), startLineNo, batch);
                    startLineNo += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                flushBatch(projectId, file.getOriginalFilename(), startLineNo, batch);
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw BizException.serviceUnavailable("文件读取失败: " + file.getOriginalFilename());
        }
        return totalLines;
    }

    private void flushBatch(String projectId, String fileName, int startLineNo, List<String> lines) {
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
             * 行号统一用 1 基。
             * 采集链路 WatchServiceLogCollector 一直是 1 基（linesRead + i + 1），
             * 而这里原来是 0 基（startLineNo + i）—— 两条入口对同一行给出不同的行号，
             * 前端日志详情里会直接显示成 filePath:lineNumber，人一眼就能看出差 1。
             * 现在和 IDEA 侧边栏、grep -n 的显示对齐。
             */
            int lineNo = startLineNo + i + 1;

            doc.setId(projectId + ":" + fileName + ":" + lineNo);
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

        boolean success = false;
        for (int retry = 0; retry < 3; retry++) {
            try {
                esLogRepository.bulkInsert(docs);
                success = true;
                break;
            } catch (Exception e) {
                log.warn("ES 写入失败，重试 {}/{}", retry + 1, 3, e);
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
        if (!success) {
            throw BizException.serviceUnavailable("ES 写入失败，重试 3 次仍不可用");
        }
        // ★ 新错误雷达：收集本批 ERROR 的 clusterId 喂雷达（批内去重后只有几个，开销可忽略）
        Set<String> errorClusters = docs.stream()
                .filter(d -> LogLevel.ERROR.name().equals(d.getLevel()))
                .map(LogEntryDoc::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!errorClusters.isEmpty()) {
            errorRadarService.scan(projectId, errorClusters);
        }
    }

    private int estimateLines(MultipartFile[] files) {
        long bytes = 0;
        for (MultipartFile f : files) {
            bytes += f.getSize();
        }
        if (bytes <= 0) return 1;
        return (int) Math.max(1, bytes / 120);
    }
}