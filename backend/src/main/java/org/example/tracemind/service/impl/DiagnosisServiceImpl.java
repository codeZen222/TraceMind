package org.example.tracemind.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tracemind.aiclient.PythonClient;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.common.PageResult;
import org.example.tracemind.common.enums.SessionStatus;
import org.example.tracemind.diagnosis.WsSessionRegistry;
import org.example.tracemind.es.EsLogRepository;
import org.example.tracemind.mapper.AgentEventMapper;
import org.example.tracemind.mapper.AgentSessionMapper;
import org.example.tracemind.mapper.ChatMessageMapper;
import org.example.tracemind.mapper.ProjectMapper;
import org.example.tracemind.pojo.dto.ChatRequestDTO;
import org.example.tracemind.pojo.dto.DiagnosisTriggerDTO;
import org.example.tracemind.pojo.entity.AgentEvent;
import org.example.tracemind.pojo.entity.AgentSession;
import org.example.tracemind.pojo.entity.ChatMessage;
import org.example.tracemind.pojo.entity.Project;
import org.example.tracemind.pojo.vo.*;
import org.example.tracemind.service.DiagnosisService;
import org.example.tracemind.service.OwnershipGuard;
import org.example.tracemind.security.CurrentUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class DiagnosisServiceImpl implements DiagnosisService {

    /** seq 发号器的 Redis key 前缀 */
    private static final String SEQ_KEY = "diag:seq:";

    /** 诊断整体超时（毫秒）。超过就判失败，不让前端无限等 */
    private static final long DIAGNOSIS_TIMEOUT_MS = 120_000L;

    private final ProjectMapper projectMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final AgentEventMapper agentEventMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final EsLogRepository esLogRepository;
    private final PythonClient pythonClient;
    private final WsSessionRegistry wsSessionRegistry;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Executor diagnosisExecutor;
    private final OwnershipGuard ownershipGuard;

    public DiagnosisServiceImpl(ProjectMapper projectMapper,
                                AgentSessionMapper agentSessionMapper,
                                AgentEventMapper agentEventMapper,
                                ChatMessageMapper chatMessageMapper,
                                EsLogRepository esLogRepository,
                                PythonClient pythonClient,
                                WsSessionRegistry wsSessionRegistry,
                                StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                @Qualifier("diagnosisExecutor") Executor diagnosisExecutor,
                                OwnershipGuard ownershipGuard) {
        // ★ 手写构造器而不是 @RequiredArgsConstructor：
        //   Lombok 默认不会把字段上的 @Qualifier 复制到构造参数，一旦参数名改了就会注入错线程池。
        //   见 §0.3 第 ② 条，同样的坑在 ImportServiceImpl 里已经出现过一次。
        this.projectMapper = projectMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.agentEventMapper = agentEventMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.esLogRepository = esLogRepository;
        this.pythonClient = pythonClient;
        this.wsSessionRegistry = wsSessionRegistry;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.diagnosisExecutor = diagnosisExecutor;
        this.ownershipGuard = ownershipGuard;
    }

    // ==================================================================
    // 触发
    // ==================================================================

    @Override
    public DiagnosisSessionVO trigger(DiagnosisTriggerDTO dto) {
        // 1) 项目必须存在，且归属当前用户
        ownershipGuard.requireOwned(dto.getProjectId());
        // 2) 触发日志必须存在，而且必须属于这个项目 —— 归属校验不能省
        LogEntryVO triggerLog = esLogRepository.getById(dto.getLogId());
        if (triggerLog == null || !dto.getProjectId().equals(triggerLog.getProjectId())) {
            throw BizException.notFound("日志不存在: " + dto.getLogId());
        }
        String sessionId = doTrigger(dto.getProjectId(), dto.getLogId(), triggerLog);
        // 立刻返回 RUNNING 的会话，前端拿 sessionId 去连 WS
        return getById(sessionId);
    }

    @Override
    public String triggerAuto(String projectId, String logId) {
        // ★ 不做归属校验：自动诊断跑在导入/采集的异步线程里，拿不到 SecurityContext。
        //   归属已在入口（startImport / monitor start）校验过，这里只做「日志存在且属于项目」。
        LogEntryVO triggerLog = esLogRepository.getById(logId);
        if (triggerLog == null || !projectId.equals(triggerLog.getProjectId())) {
            log.warn("自动诊断跳过：日志不存在或不属于项目。projectId={} logId={}", projectId, logId);
            return null;
        }
        return doTrigger(projectId, logId, triggerLog);
    }

    /**
     * 触发诊断的核心：建 RUNNING 会话 + 异步跑。trigger 与 triggerAuto 共用。
     */
    private String doTrigger(String projectId, String logId, LogEntryVO triggerLog) {
        String sessionId = IdGenerator.session();
        AgentSession session = new AgentSession();
        session.setSessionId(sessionId);
        session.setProjectId(projectId);
        session.setStatus(SessionStatus.RUNNING.name());
        session.setTriggerLogId(logId);
        session.setLlmUsed(false);
        session.setToolCallCount(0);
        agentSessionMapper.insert(session);

        // 清掉可能残留的计数器（正常情况不会有，防的是上次同名会话的脏 key）
        redis.delete(SEQ_KEY + sessionId);
        log.info("诊断已触发 sessionId={} projectId={} logId={}",
                sessionId, projectId, logId);

        // 异步跑，不等它。startMs 用于后续超时判断
        long startMs = System.currentTimeMillis();
        diagnosisExecutor.execute(() -> runDiagnosis(sessionId, projectId, triggerLog, startMs));
        return sessionId;
    }

    /**
     * 异步诊断主流程。
     *
     * <p>用 {@code .toIterable()} 阻塞迭代而<b>不是</b> {@code .subscribe(...)}：
     * 后者的事件回调跑在 Netty 的 IO 线程上，多个事件可能并发到达，
     * 而"先落库后推送"要求严格按 seq 顺序执行。放在自己的线程里顺序迭代，这些烦恼全没了。</p>
     */
    private void runDiagnosis(String sessionId, String projectId, LogEntryVO triggerLog, long startMs) {
        try {
            for (DiagnosisEventVO event : pythonClient.diagnose(projectId, sessionId, triggerLog).toIterable()) {
                if (System.currentTimeMillis() - startMs > DIAGNOSIS_TIMEOUT_MS) {
                    log.warn("诊断超过 {}ms，主动中断 sessionId={}", DIAGNOSIS_TIMEOUT_MS, sessionId);
                    handleEvent(sessionId, DiagnosisEventVO.error(0, "诊断超时"), startMs);
                    break;
                }
                handleEvent(sessionId, event, startMs);
            }
            finalizeIfRunning(sessionId, startMs);
        } catch (Exception e) {
            log.error("诊断失败 sessionId={}", sessionId, e);
            // ★ 降级而不是 500：把失败如实记成一条 error 事件，前端能展示"诊断服务不可用"
            handleEvent(sessionId, DiagnosisEventVO.error(0, "诊断服务不可用: " + rootMessage(e)), startMs);
            agentSessionMapper.updateStatus(sessionId, SessionStatus.FAILED.name());
        }
    }

    /**
     * 兜底：流正常结束了但会话还挂在 RUNNING 上 —— 说明 Python 侧没发 result 就收尾了。
     * <b>不兜底的话前端会永远转圈。</b>
     */
    private void finalizeIfRunning(String sessionId, long startMs) {
        // 1) 会话必须存在且是 RUNNING 状态
        AgentSession session = agentSessionMapper.selectById(sessionId);
        if (session == null || !SessionStatus.RUNNING.name().equals(session.getStatus())) {
            return;
        }
        log.warn("诊断流已结束但会话仍是 RUNNING，按失败收尾 sessionId={}", sessionId);
        AgentSession patch = new AgentSession();
        patch.setSessionId(sessionId);
        patch.setStatus(SessionStatus.FAILED.name());
        patch.setTotalCostMs(System.currentTimeMillis() - startMs);
        agentSessionMapper.updateResult(patch);
    }

    // ==================================================================
    // 事件：发号 → 落库 → 推送（★ 顺序不能反）
    // ==================================================================

    /**
     * 处理一个诊断事件。
     *
     * <p><b>铁律</b>：{@code seq = INCR} → {@code 落 agent_event} → {@code 推 WS}。
     * 先推后落的话，用户刷新页面做历史回放会缺事件 —— 回放读的是库，事件还没落进去。</p>
     *
     * @param startMs 本次诊断的起始时刻（{@code System.currentTimeMillis()}）。
     *                一路传进来是为了在收尾事件里算出真实的 {@code total_cost_ms} ——
     *                之前只有"超时兜底"那条分支写了耗时，正常结束的会话 total_cost_ms 恒为 null，
     *                于是前端会话列表、诊断元信息、导出报告里「耗时」全是 {@code --}。
     */
    public void handleEvent(String sessionId, DiagnosisEventVO event, long startMs) {
        // 1) 发号。Redis 挂了这里返回 null，事件只能丢 —— 但不会影响已经落库的部分
        Long seq;
        try {

            seq = redis.opsForValue().increment(SEQ_KEY + sessionId);
        } catch (Exception e) {
            log.error("Redis 不可用，无法生成 seq，该事件不落库也不推送 sessionId={} type={}",
                    sessionId, event.getType(), e);
            return;
        }
        if (seq == null) {
            log.error("seq 发号返回 null，跳过 sessionId={}", sessionId);
            return;
        }

        // 2) 先落库
        try {
            AgentEvent row = new AgentEvent();
            row.setSessionId(sessionId);
            row.setSeq(seq.intValue());
            row.setEventType(event.getType());
            row.setPayload(objectMapper.writeValueAsString(event));
            agentEventMapper.insert(row);
        } catch (Exception e) {
            // 落库失败仍然继续推送：用户至少能看到实时过程，
            // 代价是这条事件不会出现在历史回放里 —— 让它可见比让它完美重要
            log.error("事件落库失败 sessionId={} seq={} type={}", sessionId, seq, event.getType(), e);
        }

        // 3) 后推送（没人听就自动跳过，不影响落库）
        wsSessionRegistry.send(sessionId, WsMessageVO.of(seq.intValue(), sessionId, event));

        // 4) 按类型更新会话统计
        try {
            applyToSession(sessionId, event, startMs);
        } catch (Exception e) {
            log.error("更新会话统计失败 sessionId={} type={}", sessionId, event.getType(), e);
        }
    }

    private void applyToSession(String sessionId, DiagnosisEventVO event, long startMs)
            throws JsonProcessingException {
        switch (event.getType()) {
            case "rule_hit" -> {
                // 规则直接命中：没走 LLM，耗时极短
                AgentSession patch = new AgentSession();
                patch.setSessionId(sessionId);
                patch.setStatus(SessionStatus.SUCCEEDED.name());
                patch.setTotalCostMs(System.currentTimeMillis() - startMs);
                patch.setLlmUsed(false);
                patch.setMatchedAnalyzer(event.getAnalyzer());
                patch.setRootCause(event.getSolution());
                patch.setSuggestion(event.getSolution());
                agentSessionMapper.updateResult(patch);
            }
            case "tool_call" -> agentSessionMapper.incrToolCall(sessionId);
            case "result" -> {
                AgentSession patch = new AgentSession();
                patch.setSessionId(sessionId);
                patch.setStatus(SessionStatus.SUCCEEDED.name());
                patch.setTotalCostMs(System.currentTimeMillis() - startMs);
                // ★ llmUsed 不能无条件置 true。
                //   Python 侧规则命中时会连发两个事件：先 rule_hit（把 llmUsed 置 false）、
                //   再 result —— 无条件置 true 会把 rule_hit 刚写好的 false 覆盖掉，
                //   结果是一条"规则毫秒命中、根本没花 token"的会话被记成 llmUsed=true，
                //   工作台的「规则拦截率」因此恒为 0%。
                //   判据：命中过规则（matched_analyzer 非空）就不可能进阶段 3，即一定没用 LLM。
                AgentSession current = agentSessionMapper.selectById(sessionId);
                patch.setLlmUsed(current == null || current.getMatchedAnalyzer() == null);
                patch.setRootCause(event.getRootCause());
                patch.setSuggestion(event.getSuggestion());
                if (event.getConfidence() != null) {
                    // DECIMAL(3,2) 只能存 -9.99~9.99，confidence 是 0~1，安全
                    patch.setConfidence(java.math.BigDecimal.valueOf(event.getConfidence()));
                }
                // result_json 是 JSON 列：evidence / relatedLogIds 这些塞在这里
                DiagnosisResultVO result = new DiagnosisResultVO();
                result.setRootCause(event.getRootCause());
                result.setEvidence(event.getEvidence());
                result.setSuggestion(event.getSuggestion());
                result.setConfidence(event.getConfidence());
                result.setToolCallCount(current == null || current.getToolCallCount() == null
                        ? 0 : current.getToolCallCount());
                patch.setResultJson(objectMapper.writeValueAsString(result));
                agentSessionMapper.updateResult(patch);
            }
            case "error" -> agentSessionMapper.updateStatus(sessionId, SessionStatus.FAILED.name());
            default -> {
                // stage / stageDone / tool_result / token / thinking 只落库 + 推送，不改会话状态
            }
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public void requireOwnedSession(String sessionId) {
        AgentSession session = agentSessionMapper.selectById(sessionId);
        // ★ 会话不存在、以及"存在但不属于当前用户"两种情况，统一回"诊断会话不存在"——
        //   不区分，是为了不暴露"这个 sessionId 真实存在，只是项目不是你的"（账号枚举那类信息泄露）
        if (session == null || projectMapper.selectByIdAndOwner(session.getProjectId(), CurrentUser.id()) == null) {
            throw BizException.notFound("诊断会话不存在: " + sessionId);
        }
    }

    private AgentSession requireOwnedSessionEntity(String sessionId) {
        AgentSession session = agentSessionMapper.selectById(sessionId);
        if (session == null || projectMapper.selectByIdAndOwner(session.getProjectId(), CurrentUser.id()) == null) {
            throw BizException.notFound("诊断会话不存在: " + sessionId);
        }
        return session;
    }

    @Override
    public DiagnosisSessionVO getById(String sessionId) {
        AgentSession session = requireOwnedSessionEntity(sessionId);
        DiagnosisSessionVO vo = toVO(session);

        // 触发日志（前端详情页要展示"是哪条日志触发的"）
        if (session.getTriggerLogId() != null) {
            vo.setTriggerLog(esLogRepository.getById(session.getTriggerLogId()));
        }

        // 历史事件回放 —— 前端刷新页面后靠它把过程重建出来
        List<AgentEvent> rows = agentEventMapper.selectBySession(sessionId);
        List<WsMessageVO> events = new ArrayList<>(rows.size());
        for (AgentEvent row : rows) {
            try {
                DiagnosisEventVO event = objectMapper.readValue(row.getPayload(), DiagnosisEventVO.class);
                events.add(WsMessageVO.of(row.getSeq(), sessionId, event));
            } catch (Exception e) {
                // 单条脏数据不能让整个详情接口 500
                log.warn("回放事件解析失败 sessionId={} seq={}", sessionId, row.getSeq(), e);
            }
        }
        vo.setEvents(events);
        return vo;
    }

    @Override
    public PageResult<DiagnosisSessionVO> listSessions(String projectId, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 200);

        long total;
        List<AgentSession> rows;
        if (projectId == null || projectId.isBlank()) {
            // ★ 跨项目：不传 projectId = 查当前用户全部会话（按 owner 过滤，子查询 join project）
            String ownerId = CurrentUser.id();
            total = agentSessionMapper.countByOwner(ownerId);
            rows = agentSessionMapper.selectPageByOwner(ownerId, (p - 1) * s, s);
        } else {
            // 单项目：先校验归属，再按项目查
            ownershipGuard.requireOwned(projectId);
            total = agentSessionMapper.countByProject(projectId);
            rows = agentSessionMapper.selectPageByProject(projectId, (p - 1) * s, s);
        }

        // 列表接口<b>不带 events</b>：一页 20 条会话、每条几十个事件，全带上响应会大到没法看
        List<DiagnosisSessionVO> list = new ArrayList<>(rows.size());
        for (AgentSession row : rows) {
            DiagnosisSessionVO vo = toVO(row);
            // triggerLog 只要摘要；这里给全量也没问题，前端只用其中几个字段
            if (row.getTriggerLogId() != null) {
                vo.setTriggerLog(esLogRepository.getById(row.getTriggerLogId()));
            }
            list.add(vo);
        }
        return PageResult.of(list, total, p, s);
    }

    // ==================================================================
    // 追问（SSE）
    // ==================================================================

    @Override
    public SseEmitter chat(String sessionId, ChatRequestDTO dto) {
        // ★ 多租户：会话归属校验（不存在/不拥有都走 404）
        requireOwnedSession(sessionId);
        if (dto.getQuestion() == null || dto.getQuestion().isBlank()) {
            throw BizException.badRequest("问题不能为空");
        }

        SseEmitter emitter = new SseEmitter(180_000L);

        // 1) 先把用户这句话落库
        ChatMessage userMsg = new ChatMessage();
        userMsg.setId(IdGenerator.chat());
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(dto.getQuestion());
        userMsg.setTs(System.currentTimeMillis());
        chatMessageMapper.insert(userMsg);

        // 2) 异步消费 Python 的 token 流
        diagnosisExecutor.execute(() -> streamAnswer(sessionId, dto, emitter));
        return emitter;
    }

    private void streamAnswer(String sessionId, ChatRequestDTO dto, SseEmitter emitter) {
        StringBuilder answer = new StringBuilder();
        try {
            for (String raw : pythonClient.chat(sessionId, dto.getQuestion(), dto.getHistory()).toIterable()) {
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                // ★ 结束标记原样转发：前端 src/api/diagnosis.ts 就靠它判断流结束
                if ("[DONE]".equals(raw.trim())) {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    break;
                }
                // ★ 不要 trim！英文单词之间的那个前导空格就是靠它传过去的，
                //   trim 掉之后前端拼出来是 "theorderisnotfound" 这种连写
                //
                // ★ 落库只拼「文本帧」：追问接入 Agent 后，流里还有 tool_call / tool_result
                //   这些过程帧，照旧全拼进去的话，历史回放会看到一屏工具调用的 JSON。
                answer.append(textOfFrame(raw));
                // 转发仍然是原样：前端要靠过程帧显示"正在查日志…"
                emitter.send(SseEmitter.event().data(raw));
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("追问失败 sessionId={}", sessionId, e);
            emitter.completeWithError(e);
        }

        // 3) 回复落库。放在流结束之后 —— 即使落库失败，用户也已经看到完整回答了
        try {
            ChatMessage reply = new ChatMessage();
            reply.setId(IdGenerator.chat());
            reply.setSessionId(sessionId);
            reply.setRole("assistant");
            reply.setContent(answer.toString());
            reply.setTs(System.currentTimeMillis());
            chatMessageMapper.insert(reply);
        } catch (Exception e) {
            log.error("追问回复落库失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 从追问的一帧里取出"应该落库的文本"。
     *
     * <p>追问接入 Agent 之后，Python 每一帧都发一个 JSON：
     * <pre>
     *   {"type":"text","content":"这次诊断"}          ← 回答正文
     *   {"type":"tool_call","tool":"search_logs",...} ← 过程，不入库
     *   {"type":"tool_result","tool":"search_logs",...}
     * </pre>
     * 过程帧只推给前端展示，落库时丢掉。</p>
     *
     * <p>兼容老格式：不以 <code>{</code> 开头的一律当纯文本原样返回 ——
     * 帧格式切换期间两边可能不同步，这时候"多存一点"比"丢内容"安全。</p>
     */
    private String textOfFrame(String raw) {
        if (raw == null || raw.isBlank() || "[DONE]".equals(raw.trim())) {
            return "";
        }
        if (!raw.startsWith("{")) {
            return raw;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return "text".equals(node.path("type").asText()) ? node.path("content").asText("") : "";
        } catch (Exception e) {
            // 解析不了就当纯文本 —— 少存一点比整段丢掉好
            return raw;
        }
    }

    // ==================================================================
    // 转换
    // ==================================================================

    private DiagnosisSessionVO toVO(AgentSession s) {
        DiagnosisSessionVO vo = new DiagnosisSessionVO();
        vo.setSessionId(s.getSessionId());
        vo.setProjectId(s.getProjectId());
        vo.setStatus(s.getStatus());
        vo.setStartedAt(toInstant(s.getStartedAt()));
        vo.setFinishedAt(toInstant(s.getFinishedAt()));
        vo.setTotalCostMs(s.getTotalCostMs());
        vo.setLlmUsed(s.getLlmUsed());
        vo.setMatchedAnalyzer(s.getMatchedAnalyzer());

        // result：库里 result_json 有就用它（evidence / relatedLogIds 在那儿）；
        // 没有但 root_cause 有值，就退化成一个只有 rootCause 的结果对象
        if (s.getResultJson() != null && !s.getResultJson().isBlank()) {
            try {
                vo.setResult(objectMapper.readValue(s.getResultJson(), DiagnosisResultVO.class));
            } catch (Exception e) {
                log.warn("result_json 解析失败 sessionId={}", s.getSessionId(), e);
                vo.setResult(fallbackResult(s));
            }
        } else if (s.getRootCause() != null || s.getSuggestion() != null) {
            vo.setResult(fallbackResult(s));
        }
        // 项目名：前端详情页标题要显示
        Project project = projectMapper.selectById(s.getProjectId());
        if (project != null) {
            vo.setProjectName(project.getName());
        }
        return vo;
    }

    private DiagnosisResultVO fallbackResult(AgentSession s) {
        DiagnosisResultVO r = new DiagnosisResultVO();
        r.setRootCause(s.getRootCause());
        r.setSuggestion(s.getSuggestion());
        r.setConfidence(s.getConfidence() == null ? null : s.getConfidence().doubleValue());
        r.setToolCallCount(s.getToolCallCount());
        return r;
    }

    /** LocalDateTime → Instant。库里的 DATETIME 是 UTC（yml 已强制会话时区），按 UTC 解释 */
    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}