package org.example.tracemind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.common.Utilx;
import org.example.tracemind.es.EsDocId;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.es.LogVoConverter;
import org.example.tracemind.mapper.AgentSessionMapper;
import org.example.tracemind.mapper.ProjectMapper;
import org.example.tracemind.mapper.ReportMapper;
import org.example.tracemind.pojo.dto.ReportGenerateDTO;
import org.example.tracemind.pojo.entity.AgentSession;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.pojo.entity.Report;
import org.example.tracemind.pojo.vo.DiagnosisResultVO;
import org.example.tracemind.pojo.vo.LogStatsVO;
import org.example.tracemind.pojo.vo.ReportVO;
import org.example.tracemind.security.CurrentUser;
import org.example.tracemind.service.OwnershipGuard;
import org.example.tracemind.service.ReportService;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 报告生成。
 *
 * <p>整个 J7 只有这一处有真正的"业务逻辑"——把若干次诊断会话 + 日志统计，
 * 拼成一篇人类读得下去的 Markdown 复盘报告。其余都是增删查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ReportMapper reportMapper;
    private final ProjectMapper projectMapper;
    private final OwnershipGuard ownershipGuard;
    private final AgentSessionMapper agentSessionMapper;
    private final EsLogRepository esLogRepository;
    private final ObjectMapper jsonMapper;

    @Override
    public ReportVO generate(ReportGenerateDTO dto) {
        // 1) 校验
        if (dto.getProjectId() == null || dto.getProjectId().isBlank()) {
            throw BizException.badRequest("projectId 不能为空");
        }
        // ★ 多租户：项目归属当前用户
        Project project = ownershipGuard.requireOwned(dto.getProjectId());
        if (dto.getSessionIds() == null || dto.getSessionIds().isEmpty()) {
            throw BizException.badRequest("sessionIds 不能为空，至少选一次诊断");
        }

        // 2) 一次取全部会话，避免 N+1
        List<AgentSession> sessions = agentSessionMapper.selectByIds(dto.getSessionIds());
        if (sessions.isEmpty()) {
            throw BizException.notFound("选中的诊断会话都不存在");
        }
        // 归属校验：会话必须属于这个项目，否则就是越权拿别人的诊断
        for (AgentSession s : sessions) {
            if (!dto.getProjectId().equals(s.getProjectId())) {
                throw BizException.badRequest("诊断会话 " + s.getSessionId() + " 不属于该项目");
            }
        }
        return doGenerate(project, sessions);
    }

    @Override
    public ReportVO generateAuto(String projectId, List<String> sessionIds) {
        if (projectId == null || projectId.isBlank() || sessionIds == null || sessionIds.isEmpty()) {
            return null;
        }
        // ★ 不做归属校验（异步线程拿不到 SecurityContext，入口已校验过）
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return null;
        }
        List<AgentSession> sessions = agentSessionMapper.selectByIds(sessionIds);
        if (sessions.isEmpty()) {
            return null;
        }
        for (AgentSession s : sessions) {
            if (!projectId.equals(s.getProjectId())) {
                return null;
            }
        }
        return doGenerate(project, sessions);
    }

    /** 生成报告的核心：拼 Markdown + 落库。generate 与 generateAuto 共用 */
    private ReportVO doGenerate(Project project, List<AgentSession> sessions) {
        // 日志统计（ES 挂了就降级成空统计，不让报告生成失败）
        LogStatsVO stats = null;
        try {
            stats = LogVoConverter.toStatsVO(esLogRepository.aggregate(project.getId()));
        } catch (Exception e) {
            log.warn("生成报告时取日志统计失败，降级为空统计 projectId={}", project.getId(), e);
        }

        String markdown = renderMarkdown(project, sessions, stats);

        Report report = new Report();
        report.setId(IdGenerator.report());
        report.setProjectId(project.getId());
        report.setTitle(project.getName() + " 故障复盘报告");
        List<String> sessionIds = sessions.stream().map(AgentSession::getSessionId).toList();
        String sessionIdsJson;
        try {
            sessionIdsJson = jsonMapper.writeValueAsString(sessionIds);
        } catch (JsonProcessingException e) {
            throw new BizException("sessionIds 序列化失败: " + e.getMessage());
        }
        report.setSessionIds(sessionIdsJson);   // JSON 列
        report.setMarkdown(markdown);
        report.setLogCount(stats == null ? 0 : totalLogs(stats));
        report.setDiagnosisCount(sessions.size());
        reportMapper.insert(report);

        log.info("报告已生成 id={} projectId={} 会话 {} 个",
                report.getId(), project.getId(), sessions.size());

        // ★ 插完再查一次：created_at 由 DB 的 DEFAULT CURRENT_TIMESTAMP 填，
        //   MySQL 不会回填到 Java 对象，直接返回 report 会得到 createdAt=null
        return toVO(reportMapper.selectById(report.getId()));
    }

    @Override
    public ReportVO getById(String id) {
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw BizException.notFound("报告不存在: " + id);
        }
        // ★ 多租户：报告归属校验
        ownershipGuard.requireOwned(report.getProjectId());
        return toVO(report);
    }

    @Override
    public PageResult<ReportVO> list(String projectId, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 200);
        String pid = (projectId == null || projectId.isBlank()) ? null : projectId;

        // ★ 多租户：报告列表只返回当前用户的（SQL 里 join project 按 owner 过滤）
        String ownerId = CurrentUser.id();
        long total = reportMapper.countByCondition(ownerId, pid);
        List<Report> rows = reportMapper.selectPage(ownerId, pid, (p - 1) * s, s);

        List<ReportVO> list = new ArrayList<>(rows.size());
        for (Report row : rows) {
            list.add(toVO(row));      // ★ markdown 原样带上，不截断
        }
        return PageResult.of(list, total, p, s);
    }

    // ==================================================================
    // Markdown 渲染
    // ==================================================================

    /**
     * 把触发日志的引用渲染成「文件名:行号」，而不是完整的 ES 文档 ID。
     *
     * <p>完整 ID 形如 {@code p-pocxliuw:app.log:510} —— 它是 ES 的<b>主键</b>，
     * 会随"重新导入"整批变化（曾经因为行号基准从 0 基改成 1 基，同一行从 {@code :509} 变成 {@code :510}）。
     * 报告是<b>生成时刻的快照</b>，把这种会变的主键冻进去，重导一次报告里的引用就全成了死链。</p>
     *
     * <p>而 {@code app.log:510} 指向的是<b>原始日志文件</b>里的位置，人可以直接去翻文件核对，不会失效。</p>
     */
    private String logRef(String triggerLogId) {
        if (triggerLogId == null || triggerLogId.isBlank()) {
            return "—";
        }
        // 交给 EsDocId：此前这里是第三份手写实现（自己 indexOf(':') 拆），
        // 与生成端的两份实现没有任何同步机制 —— ID 加一段就会错位。
        return EsDocId.withoutProject(triggerLogId);
    }

    private String renderMarkdown(Project project, List<AgentSession> sessions, LogStatsVO stats) {
        StringBuilder sb = new StringBuilder(4096);

        // ---- 标题与概览 ----
        sb.append("# ").append(project.getName()).append(" 故障复盘报告\n\n");
        sb.append("> 生成时间：").append(TIME_FMT.format(Instant.now()))
                .append(" ｜ 涉及诊断 ").append(sessions.size()).append(" 次");
        if (stats != null) {
            sb.append(" ｜ 日志 ").append(totalLogs(stats)).append(" 条");
        }
        sb.append("\n\n");

        // ---- 一、故障概述 ----
        sb.append("## 一、故障概述\n\n");
        for (int i = 0; i < sessions.size(); i++) {
            AgentSession s = sessions.get(i);
            sb.append(i + 1).append(". **").append(s.getSessionId()).append("**  ");
            sb.append("日志位置：`").append(logRef(s.getTriggerLogId())).append("`  ");
            sb.append("开始：").append(format(s.getStartedAt())).append("  ");
            sb.append("耗时：").append(s.getTotalCostMs() == null ? "—" : s.getTotalCostMs() + " ms");
            sb.append("\n");
        }
        sb.append("\n");

        // ---- 二、根因分析 ----
        sb.append("## 二、根因分析\n\n");
        for (AgentSession s : sessions) {
            DiagnosisResultVO result = parseResult(s);
            sb.append("### ").append(s.getSessionId()).append("\n\n");
            sb.append("**根因**：").append(nullToDash(
                            result != null && result.getRootCause() != null ? result.getRootCause() : s.getRootCause()))
                    .append("\n\n");

            if (result != null && result.getEvidence() != null && !result.getEvidence().isEmpty()) {
                sb.append("**证据**：\n\n");
                for (String ev : result.getEvidence()) {
                    sb.append("- ").append(ev).append("\n");
                }
                sb.append("\n");
            }
            sb.append("**置信度**：").append(formatConfidence(result, s)).append("  ");
            sb.append("**命中规则**：").append(nullToDash(s.getMatchedAnalyzer())).append("  ");
            sb.append("**使用 LLM**：").append(Boolean.TRUE.equals(s.getLlmUsed()) ? "是" : "否").append("\n\n");
        }

        // ---- 三、修复建议（去重，用 LinkedHashSet 保序） ----
        Set<String> suggestions = new LinkedHashSet<>();
        for (AgentSession s : sessions) {
            DiagnosisResultVO result = parseResult(s);
            String sug = result != null && result.getSuggestion() != null
                    ? result.getSuggestion() : s.getSuggestion();
            if (sug != null && !sug.isBlank()) {
                suggestions.add(sug.trim());
            }
        }
        sb.append("## 三、修复建议\n\n");
        if (suggestions.isEmpty()) {
            sb.append("_本次诊断未产出修复建议。_\n\n");
        } else {
            int idx = 1;
            for (String sug : suggestions) {
                sb.append(idx++).append(". ").append(sug).append("\n");
            }
            sb.append("\n");
        }

        // ---- 四、数据附录 ----
        sb.append("## 四、数据附录\n\n");
        if (stats == null) {
            sb.append("_日志统计不可用（Elasticsearch 未就绪）。_\n");
        } else {
            sb.append("### 级别分布\n\n");
            sb.append("| 级别 | 数量 |\n|---|---|\n");
            if (stats.getLevelDistribution() != null) {
                for (LogStatsVO.LevelDistributionItem lc : stats.getLevelDistribution()) {
                    sb.append("| ").append(lc.getLevel()).append(" | ").append(lc.getCount()).append(" |\n");
                }
            }
            sb.append("\n### 高频模板 TOP").append(
                    stats.getTopTemplates() == null ? 0 : stats.getTopTemplates().size()).append("\n\n");
            sb.append("| 模板 | 次数 |\n|---|---|\n");
            if (stats.getTopTemplates() != null) {
                for (LogStatsVO.TopTemplateItem tc : stats.getTopTemplates()) {
                    sb.append("| `").append(escapeCell(tc.getTemplate())).append("` | ")
                            .append(tc.getCount()).append(" |\n");
                }
            }
            sb.append("\n");
        }

        sb.append("---\n\n*本报告由 TraceMind 自动生成，根因结论仅供参考。*\n");
        return sb.toString();
    }

    /** 把 result_json 解析回来。失败返回 null，渲染时退化用表字段 */
    private DiagnosisResultVO parseResult(AgentSession s) {
        if (s.getResultJson() == null || s.getResultJson().isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(s.getResultJson(), DiagnosisResultVO.class);
        } catch (Exception e) {
            log.warn("result_json 解析失败，退化为表字段 sessionId={}", s.getSessionId(), e);
            return null;
        }
    }

    private int totalLogs(LogStatsVO stats) {
        if (stats.getLevelDistribution() == null) {
            return 0;
        }
        long sum = 0;
        for (LogStatsVO.LevelDistributionItem lc : stats.getLevelDistribution()) {
            if (lc.getCount() != null) {
                sum += lc.getCount();
            }
        }
        return (int) sum;
    }

    /** Markdown 表格里的 | 会把列切断，必须转义（常见于代码行） */
    private String escapeCell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private String format(LocalDateTime t) {
        // 时区假设统一走 Utilx（此处原是一份内联的 toInstant(UTC)）
        Instant instant = Utilx.toInstant(t);
        return instant == null ? "—" : TIME_FMT.format(instant);
    }

    private String formatConfidence(DiagnosisResultVO result, AgentSession s) {
        Double c = result != null && result.getConfidence() != null
                ? result.getConfidence()
                : (s.getConfidence() == null ? null : s.getConfidence().doubleValue());
        return c == null ? "—" : String.format("%.0f%%", c * 100);
    }

    private String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    // ==================================================================
    // 转换
    // ==================================================================

    private ReportVO toVO(Report r) {
        ReportVO vo = new ReportVO();
        vo.setId(r.getId());
        vo.setProjectId(r.getProjectId());
        vo.setTitle(r.getTitle());
        vo.setMarkdown(r.getMarkdown());
        vo.setLogCount(r.getLogCount());
        vo.setDiagnosisCount(r.getDiagnosisCount());
        vo.setCreatedAt(Utilx.toInstant(r.getCreatedAt()));

        // session_ids 是 JSON 列，读出来是字符串，转回 List
        if (r.getSessionIds() != null && !r.getSessionIds().isBlank()) {
            try {
                vo.setSessionIds(jsonMapper.readValue(r.getSessionIds(), new TypeReference<List<String>>() { }));
            } catch (Exception e) {
                log.warn("session_ids 解析失败 reportId={}", r.getId(), e);
                vo.setSessionIds(List.of());
            }
        } else {
            vo.setSessionIds(List.of());
        }
        return vo;
    }
}