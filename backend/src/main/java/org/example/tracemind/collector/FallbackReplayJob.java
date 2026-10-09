
package org.example.tracemind.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.config.TraceMindProperties;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.pojo.entity.LogEntryDoc;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 兜底文件重推任务：扫描本地 fallback 目录，把 ES 写入失败时落盘的日志重新推回去。
 *
 * <p>每分钟扫一次，逐个文件读 → bulk 写 ES → 成功就删文件。
 * 写失败不删，下次继续重试，直到 ES 恢复。</p>
 */
@Slf4j
@Component
public class FallbackReplayJob {

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

    @Scheduled(fixedDelay = 60_000)
    public void replay() {
        Path fallbackDir = Paths.get(properties.getFallback().getDir());
        if (!Files.exists(fallbackDir) || !Files.isDirectory(fallbackDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(fallbackDir)) {
            List<Path> fallbackFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();

            for (Path file : fallbackFiles) {
                replayFile(file);
            }
        } catch (IOException e) {
            log.warn("扫描兜底目录失败 dir={}", fallbackDir, e);
        }
    }

    private void replayFile(Path file) {
        List<LogEntryDoc> docs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    docs.add(objectMapper.readValue(line, LogEntryDoc.class));
                }
            }
        } catch (IOException e) {
            log.error("读取兜底文件失败 file={}", file, e);
            return;
        }

        if (docs.isEmpty()) {
            deleteQuietly(file);
            return;
        }

        try {
            esLogRepository.bulkInsert(docs);
            log.info("兜底文件重推成功 file={} count={}", file.getFileName(), docs.size());
            deleteQuietly(file);
        } catch (Exception e) {
            log.warn("兜底文件重推失败，下次再试 file={} count={}", file.getFileName(), docs.size(), e);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除兜底文件失败 file={}", file, e);
        }
    }
}