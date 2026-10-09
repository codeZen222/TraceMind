package org.example.tracemind.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.enums.SessionStatus;
import org.example.tracemind.config.AutoDiagnosisSwitch;
import org.example.tracemind.config.AsyncConfig;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.AgentSessionMapper;
import org.example.tracemind.mapper.ErrorBaselineMapper;
import org.example.tracemind.pojo.entity.AgentSession;
import org.example.tracemind.pojo.entity.ErrorBaseline;
import org.example.tracemind.pojo.vo.NewErrorVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 新错误雷达 —— 对比「历史基线」找出从没见过的错误模板，并自动诊断。
 *
 * <p>核心一个方法 {@link #scan}：给一批 ERROR 日志的 clusterId，返回「历史上从没出现过的」，
 * 把它们写进基线表；若开启自动诊断，还会对前几个新错误自动触发诊断、诊断完自动生成报告。</p>
 *
 * <p>三个已想清楚的性能设计：</p>
 * <ol>
 *   <li><b>判新对象是「模板」不是「行」</b> —— clusterId 去重后再比对，805 条日志只有 10 个指纹。</li>
 *   <li><b>内存缓存是加速层、MySQL 是事实层</b> —— 缓存命中不碰库，miss 才查库，
 *       而 miss 恰是「新错误 / 重启后首次」，频率极低。</li>
 *   <li><b>不用 Redis 做缓存</b> —— 数据量几 KB，内存更快，且不引入「Redis 挂了雷达就瘫」的单点。</li>
 * </ol>
 *
 * <p>⚠️ {@link #scan} 会被采集线程（异步、拿不到 SecurityContext）调用，所以<b>不做归属校验</b>，
 * 归属在入口（导入/监听 start）已经校验过。自动诊断用的 {@code triggerAuto}/{@code generateAuto}
 * 同理，都是「内部版」，跳过归属校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorRadarService {

    private final ErrorBaselineMapper baselineMapper;
    private final EsLogRepository esLogRepository;
    private final DiagnosisService diagnosisService;
    private final ReportService reportService;
    private final AgentSessionMapper agentSessionMapper;
    private final AutoDiagnosisSwitch autoDiagnosisSwitch;
    @Qualifier(AsyncConfig.AUTO_DIAGNOSIS_EXECUTOR)
    private final ThreadPoolTaskExecutor autoDiagnosisExecutor;

    /** 内存缓存：projectId → 已见过的 clusterId 集合（易失，miss 时查库懒加载） */
    private final ConcurrentHashMap<String, Set<String>> seenCache = new ConcurrentHashMap<>();

    /** 等诊断完成的最长等待（诊断本身 120s 超时 + 缓冲） */
    private static final long WAIT_REPORT_MS = 180_000L;

    /**
     * 扫一批 clusterId，返回「新错误」，并更新基线。
     * 幂等：同一个 clusterId 重复扫不会重复报新。
     */
    public List<String> scan(String projectId, Collection<String> clusterIds) {
        Set<String> uniq = clusterIds.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        if (uniq.isEmpty()) {
            return List.of();
        }

        // ★ 冷启动：基线为空（第一次导入）→ 全部写入基线（acknowledged=1），返回空，不报新。
        //   否则第一次会被几十个「新错误」刷屏，反而看不清。
        if (baselineMapper.countByProject(projectId) == 0) {
            for (String cid : uniq) {
                baselineMapper.insertBaseline(projectId, cid);
            }
            markSeenAll(projectId, uniq);
            log.info("项目 {} 首次建立错误基线，共 {} 个模板", projectId, uniq.size());
            return List.of();
        }

        List<String> fresh = new ArrayList<>();
        for (String cid : uniq) {
            if (seen(projectId, cid)) {
                continue;                                   // ① 内存命中 → 旧
            }
            if (baselineMapper.exists(projectId, cid) > 0) { // ② 内存 miss → 查库（事实层）
                markSeen(projectId, cid);
                continue;
            }
            baselineMapper.insert(projectId, cid);           // ③ 库也没有 → 真·新错误
            markSeen(projectId, cid);
            fresh.add(cid);
        }
        if (!fresh.isEmpty()) {
            log.info("项目 {} 发现 {} 个新错误模板: {}", projectId, fresh.size(), fresh);
            // ★ 自动诊断：发现新错误后自动触发诊断 + 出报告（受开关和限流控制）
            autoDiagnose(projectId, fresh);
        }
        return fresh;
    }

    /** 查项目「未读」的新错误列表（前端面板用） */
    public List<NewErrorVO> newErrors(String projectId) {
        List<ErrorBaseline> rows = baselineMapper.selectUnacked(projectId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> clusterIds = rows.stream()
                .map(ErrorBaseline::getClusterId)
                .collect(Collectors.toList());

        // 一次 ES 聚合拿回模板 + 条数，避免 N+1
        var summaries = esLogRepository.clusterSummaries(projectId, clusterIds);

        List<NewErrorVO> list = new ArrayList<>(rows.size());
        for (ErrorBaseline row : rows) {
            NewErrorVO vo = new NewErrorVO();
            vo.setClusterId(row.getClusterId());
            vo.setFirstSeenAt(toIso(row.getFirstSeenAt()));
            var summary = summaries.get(row.getClusterId());
            if (summary != null) {
                vo.setTemplate(summary.template());
                vo.setCount(summary.count());
            }
            list.add(vo);
        }
        return list;
    }

    /** 标记已读（前端「知道了」按钮） */
    public void ack(String projectId, String clusterId) {
        baselineMapper.ack(projectId, clusterId);
    }

    // ==================================================================
    // 自动诊断闭环
    // ==================================================================

    /**
     * 对新错误自动触发诊断，诊断完成后自动生成报告。
     * 全程不抛异常——自动诊断是「锦上添花」，挂了不能拖垮导入/采集主流程。
     */
    private void autoDiagnose(String projectId, List<String> freshClusters) {
        if (!autoDiagnosisSwitch.isEnabled()) {
            log.info("自动诊断开关已关闭，跳过。projectId={}", projectId);
            return;   // 开关关了
        }
        // ★ 整个闭环异步执行：① bulk 写入后 ES 要 ~1s 才 refresh，立刻查会得空；
        //   ② 不阻塞导入/采集线程。
        autoDiagnosisExecutor.execute(() -> doAutoDiagnose(projectId, freshClusters));
    }

    private void doAutoDiagnose(String projectId, List<String> freshClusters) {
        try {
            // ★ 等 ES refresh（近实时，默认 ~1s），否则刚写入的日志聚合查不到
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // ① 给每个新错误找一条代表日志，限流到 limit 个
            int limit = autoDiagnosisSwitch.getLimit();
            List<String> targetLogIds = new ArrayList<>();
            for (String clusterId : freshClusters) {
                if (targetLogIds.size() >= limit) {
                    break;
                }
                var summary = esLogRepository.clusterSummaries(projectId, List.of(clusterId)).get(clusterId);
                if (summary != null && summary.sampleLogId() != null) {
                    targetLogIds.add(summary.sampleLogId());
                }
            }
            if (targetLogIds.isEmpty()) {
                log.warn("自动诊断：没找到代表日志，跳过。fresh={}", freshClusters);
                return;
            }

            // ② 触发诊断（内部版，不做归属校验）
            List<String> sessionIds = new ArrayList<>();
            for (String logId : targetLogIds) {
                String sid = diagnosisService.triggerAuto(projectId, logId);
                if (sid != null) {
                    sessionIds.add(sid);
                }
            }
            if (sessionIds.isEmpty()) {
                log.warn("自动诊断：触发诊断全部失败，跳过。logIds={}", targetLogIds);
                return;
            }
            log.info("自动诊断已触发 {} 个会话: {}", sessionIds.size(), sessionIds);

            // ③ 等诊断完成 → 生成报告（单线程池里同步执行，避免嵌套 submit 排队）
            waitAndReport(projectId, sessionIds);
        } catch (Exception e) {
            log.warn("自动诊断编排失败 projectId={} 原因={}", projectId, e.getMessage(), e);
        }
    }

    /** 异步轮询等所有诊断完成，然后生成报告 */
    private void waitAndReport(String projectId, List<String> sessionIds) {
        long deadline = System.currentTimeMillis() + WAIT_REPORT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<AgentSession> sessions = agentSessionMapper.selectByIds(sessionIds);
            boolean allDone = sessions.size() >= sessionIds.size()
                    && sessions.stream().noneMatch(s -> SessionStatus.RUNNING.name().equals(s.getStatus()));
            if (allDone) {
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 都完成（或超时，FAILED 也有结论）就生成报告
        try {
            reportService.generateAuto(projectId, sessionIds);
            log.info("自动诊断报告已生成 projectId={} 会话 {} 个", projectId, sessionIds.size());
        } catch (Exception e) {
            log.warn("自动生成报告失败 projectId={} 原因={}", projectId, e.getMessage());
        }
    }

    // ==================================================================
    // 内存缓存辅助
    // ==================================================================

    private boolean seen(String projectId, String clusterId) {
        Set<String> set = seenCache.get(projectId);
        return set != null && set.contains(clusterId);
    }

    private void markSeen(String projectId, String clusterId) {
        seenCache.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(clusterId);
    }

    private void markSeenAll(String projectId, Set<String> clusterIds) {
        seenCache.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).addAll(clusterIds);
    }

    /** 库里 DATETIME 存 UTC，转 ISO 字符串给前端 */
    private String toIso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }
}
