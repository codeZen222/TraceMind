
package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.BulkResult;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 兜底文件重推任务：扫描本地 fallback 目录，把 ES 写入失败时落盘的日志重新推回去。
 *
 * <p>每分钟扫一次（{@code fixedDelay}，即上一轮跑完再等 60 秒，不会自我重叠），
 * 逐个文件读 → bulk 写 ES → 全成功就删文件。</p>
 *
 * <h3>三类出口，各归各位</h3>
 * <pre>
 *   fallback/es-retry/*.jsonl     待重放（正常态）
 *   fallback/es-retry/dead/       重试超上限的毒消息 —— 保留现场、停止陪跑、告警
 *   fallback/es-retry/corrupt/    JSON 解析不了的坏行 —— 隔离，不阻塞同文件其余数据
 * </pre>
 *
 * <h3>⚠️ 为什么这里必须逐条看结果而不是"没抛异常就算成功"</h3>
 * ES bulk 的部分失败是 HTTP 200 + {@code errors=true}，压根不抛异常。原先"不抛就删文件"的写法
 * 会让一个文件里几条写不进去的文档<b>随整个文件一起消失</b>，且永远不会再重试。
 * 现在只有 {@link BulkResult#allSucceeded()} 为真才删；部分成功则把剩余原文重写回文件，
 * 让下一轮只处理真正还没成功的条目。
 */
@Slf4j
@Component
public class FallbackReplayJob {

    /** 毒消息归档子目录（相对 fallback.dir） */
    private static final String DEAD_DIR = "dead";
    /** 坏行归档子目录（相对 fallback.dir） */
    private static final String CORRUPT_DIR = "corrupt";
    /** 重试次数记在文件名里：fallback-xxx.jsonl → fallback-xxx.attempt7.jsonl */
    private static final String ATTEMPT_INFIX = ".attempt";

    private final EsLogRepository esLogRepository;
    private final TraceMindProperties properties;
    private final ObjectMapper objectMapper;

    public FallbackReplayJob(EsLogRepository esLogRepository,
                             TraceMindProperties properties,
                             ObjectMapper objectMapper) {
        this.esLogRepository = esLogRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 每分钟执行一次，重推所有兜底文件。
     */
    @Scheduled(fixedDelay = 60_000)
    public void replay() {
        Path fallbackDir = Paths.get(properties.getFallback().getDir());
        if (!Files.exists(fallbackDir) || !Files.isDirectory(fallbackDir)) {
            return;   // 正常情况就该不存在：ES 健康时根本不会有这些文件
        }

        try (Stream<Path> files = Files.list(fallbackDir)) {
            List<Path> pending = files
                    .filter(Files::isRegularFile)
                    // 只认主目录里的待重放文件；dead/ 与 corrupt/ 是子目录，靠 isRegularFile 已排除
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    // 文件名含时间戳，字典序即时间序 —— 保证早产生的数据先回灌，ES 里时间线不因乱序重放而颠倒
                    .sorted()
                    .toList();

            for (Path file : pending) {
                try {
                    replayFile(file);
                } catch (Exception e) {
                    // 单个文件出任何意外都不能中断整批扫描
                    log.error("重放兜底文件时发生意外，跳过该文件 file={}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描兜底目录失败 dir={}", fallbackDir, e);
        }
    }

    // ==================================================================
    // 单个文件的重放
    // ==================================================================

    private void replayFile(Path file) {
        /*
         * ★ 逐行解析、坏行就地隔离。
         *   原先整个读取包在一个 try 里、任一行解析失败就 return ——
         *   进程崩溃时写了一半的那一行，会让这个文件其余所有正常数据永远无法回灌，
         *   而且每分钟失败一次无限循环。现在坏行移走、好行照常被重放。
         */
        List<LogEntryDoc> docs = new ArrayList<>();
        List<String> corruptLines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    docs.add(objectMapper.readValue(line, LogEntryDoc.class));
                } catch (IOException parseFailed) {
                    // 单行问题，不影响同文件其余数据 —— 但它需要人工看一眼，所以留档而非丢弃
                    corruptLines.add(line);
                    log.error("兜底文件存在无法解析的行（可能写了一半）file={} 原因={}",
                            file.getFileName(), parseFailed.getMessage());
                }
            }
        } catch (IOException e) {
            // 注意：读文件本身的 IO 失败要保留文件下次再试，绝不能和"解析失败"混为一谈
            log.error("读取兜底文件失败，本次跳过（文件保留）file={}", file.getFileName(), e);
            return;
        }

        // 坏行先归档，避免它们留在原地每轮重复触发
        if (!corruptLines.isEmpty()) {
            archiveLinesInto(file, corruptLines, CORRUPT_DIR);
        }

        if (docs.isEmpty()) {
            // 全是空行或全是坏行：没有可重放的内容，原文件可以安全删除（坏行已留档）
            deleteQuietly(file);
            log.info("兜底文件无可重放内容，已删除 file={}", file.getFileName());
            return;
        }

        BulkResult result;
        try {
            result = esLogRepository.bulkInsert(docs);
        } catch (Exception e) {
            // 整批都没确认（ES 连不上／集群级拒绝）：文件原样保留，并累计一次尝试
            log.warn("兜底文件重推失败，下次再试 file={} count={} 原因={}",
                    file.getFileName(), docs.size(), e.getMessage());
            bumpAttemptAndMaybeQuarantine(file);
            return;
        }

        if (result.allSucceeded()) {
            log.info("兜底文件重推成功 file={} count={}", file.getFileName(), docs.size());
            deleteQuietly(file);
            return;
        }
        /*
         * ★ 部分失败：绝不能删文件（那是永久丢数据）。
         *   也不只是"留着不动" —— 那样已成功的那批下次会被再写一遍（ID 幂等不错数据，但纯浪费），
         *   而且毒消息会永远排在文件头反复陪跑。所以用剩余原文重写文件，让它收敛到只剩真失败的。
         */
        List<LogEntryDoc> remainder = result.failedDocs();
        log.error("兜底文件部分写入 file={} 总数={} 成功={} 仍待重试={} 首个原因={}",
                file.getFileName(), docs.size(), result.successCount(), remainder.size(),
                result.failed().get(0).reason());

        if (rewriteRemainder(file, remainder)) {
            // 只有剩余确实落盘了才计次：否则下轮读到的是旧内容，计数会与数据不符
            bumpAttemptAndMaybeQuarantine(file);
        } else {
            // 重写失败：宁可保留原文件整份下轮重来，也不能把它变成"既没写进 ES 也没了副本"
            log.error("兜底文件剩余重写失败，保留原文件下轮整体重试 file={} 丢失风险=高",
                    file.getFileName());
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 用剩余文档覆盖重写原文件。返回是否成功。 */
    private boolean rewriteRemainder(Path file, List<LogEntryDoc> remainder) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (LogEntryDoc doc : remainder) {
                writer.write(objectMapper.writeValueAsString(doc));
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("写临时兜底文件失败 file={}", file.getFileName(), e);
            deleteQuietly(tmp);
            return false;
        }
        try {
            // 原子替换：先写完再 move，中途崩溃也不会留下半个文件
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.error("替换兜底文件失败 file={}", file.getFileName(), e);
            deleteQuietly(tmp);
            return false;
        }
    }

    /**
     * 累加重试次数；超过上限就把文件移入 dead/ 归档。
     *
     * <p>次数直接编码在文件名里（不引入 sidecar），这样即使进程重启也不会丢计数，
     * 也让运维一眼看出某个文件已经被重试过多少轮。</p>
     */
    private void bumpAttemptAndMaybeQuarantine(Path file) {
        int attempts = parseAttempts(file.getFileName().toString()) + 1;
        int limit = properties.getFallback().getMaxReplayAttempts();

        if (attempts >= limit) {
            moveToSubDir(file, DEAD_DIR);
            log.error("★ 兜底文件重试已达上限 {} 次仍写不进 ES，移入 {} 目录等待人工处理。"
                            + "其中数据不会再自动回灌 —— 若属重要日志需手工修复 mapping 后放回。file={}",
                    limit, DEAD_DIR, file.getFileName());
            return;
        }

        String renamed = stripAttemptSuffix(file.getFileName().toString())
                + ATTEMPT_INFIX + attempts + ".jsonl";
        try {
            Files.move(file, file.resolveSibling(renamed));
        } catch (IOException e) {
            log.warn("更新重试次数失败（不影响下次重放）file={} 原因={}", file.getFileName(), e.getMessage());
        }
    }

    /** 从文件名尾部解析已重试次数；无标记视为 0。 */
    private int parseAttempts(String fileName) {
        int idx = fileName.indexOf(ATTEMPT_INFIX);
        if (idx < 0) {
            return 0;
        }
        String tail = fileName.substring(idx + ATTEMPT_INFIX.length()).replace(".jsonl", "");
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String stripAttemptSuffix(String fileName) {
        int idx = fileName.indexOf(ATTEMPT_INFIX);
        return idx < 0 ? fileName.replace(".jsonl", "") : fileName.substring(0, idx);
    }

    /** 把无法解析的原始行留档，保住排查现场（转义交给 JSON 字符串，不用自己拼引号）。 */
    private void archiveLinesInto(Path source, List<String> lines, String subDir) {
        Path target = Paths.get(properties.getFallback().getDir(), subDir,
                source.getFileName() + ".badlines");
        try {
            Files.createDirectories(target.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String line : lines) {
                    writer.write(objectMapper.writeValueAsString(line));
                    writer.newLine();
                }
            }
            log.warn("已归档 {} 条无法解析的行到 {}", lines.size(), target);
        } catch (IOException e) {
            log.error("归档坏行失败 dir={} 原因={}", target, e.getMessage());
        }
    }

    /** 把整个文件移进子目录（dead/）。 */
    private void moveToSubDir(Path file, String subDir) {
        Path target = Paths.get(properties.getFallback().getDir(), subDir, file.getFileName().toString());
        try {
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("移动文件到 {} 失败 file={} 原因={}", subDir, file.getFileName(), e.getMessage());
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除文件失败 file={}", file, e);
        }
    }
}
