import dayjs from 'dayjs'
import type { DiagnosisSession, WsMessage, DiagnosisEvent } from '@/types/diagnosis'
import { HIKARI_TRIGGER_LOG, NPE_TRIGGER_LOG } from './log'

/** 事件构造器：seq 从 1 连续自增 */
function ev(sessionId: string) {
  let seq = 0
  const base = dayjs().subtract(3, 'minute').valueOf()
  return (event: DiagnosisEvent): WsMessage => ({
    seq: ++seq,
    sessionId,
    ts: base + seq * 1400,
    event,
  })
}

/** 会话 A：三阶段全流程（阶段2未命中 → LangGraph Agent，3 次工具调用 + 12 个 token） */
function buildAgentSession(sessionId: string, projectId = 'p-001', projectName = 'order-service 订单中心'): DiagnosisSession {
  const e = ev(sessionId)
  const tokenText =
    '根因定位：OrderService.createOrder 第 87 行在调用 order.getAddress().getCity() 之前没有对 address 判空。' +
    '结合 search_logs 的检索结果，同一时间窗口内有 9 条相同堆栈的 NPE，' +
    '且都来自第三方导入通道的订单。read_source_code 证实 87 行直接链式调用，' +
    '而 Order 导入逻辑允许地址为空。综上，这是一个典型的上游数据未校验导致的空指针缺陷，'
  const tokens = [
    '根因定位：OrderService.createOrder 第 87 行在调用 ',
    'order.getAddress().getCity() 之前没有对 address 判空。',
    '结合 search_logs 的检索结果，',
    '同一时间窗口内有 9 条相同堆栈的 NPE，',
    '且都来自第三方导入通道的订单。',
    'read_source_code 证实 87 行直接链式调用，',
    '而 Order 导入逻辑允许地址为空。',
    '综上，这是一个典型的上游数据未校验导致的空指针缺陷，',
    '建议在 createOrder 入口处增加 address 非空校验，',
    '为空时回退到默认收货地址并记录 WARN 日志；',
    '同时给导入通道补齐字段校验，',
    '把问题拦在数据入口。',
  ]
  const events: WsMessage[] = [
    e({ type: 'stage', stage: 1, name: '日志预处理 · Drain3 模板提取', status: 'running' }),
    e({ type: 'stage', stage: 1, name: '日志预处理 · Drain3 模板提取', status: 'done', costMs: 32 }),
    e({ type: 'stage', stage: 2, name: '规则引擎预筛 · AnalyzerRegistry', status: 'running' }),
    e({ type: 'stage', stage: 2, name: '规则引擎预筛 · AnalyzerRegistry', status: 'done', costMs: 3 }),
    e({ type: 'stage', stage: 3, name: 'LangGraph Agent 深度诊断', status: 'running' }),
    e({ type: 'thinking', content: '触发日志是 NullPointerException，先检索同时间窗口的相关日志，确认影响面与堆栈聚集位置。' }),
    e({ type: 'tool_call', tool: 'search_logs', args: { keyword: 'NullPointerException', level: 'ERROR', window: '10m' } }),
    e({ type: 'tool_result', tool: 'search_logs', summary: '检索到 9 条相关日志，堆栈全部聚集在 OrderService.createOrder（app.log:1204-1210），同时段无其他异常模板。', truncated: false, costMs: 45 }),
    e({ type: 'thinking', content: '堆栈指向 OrderService.java:87，读取源码确认变量来源与调用链。' }),
    e({ type: 'tool_call', tool: 'read_source_code', args: { file: 'OrderService.java', line: 87 } }),
    e({ type: 'tool_result', tool: 'read_source_code', summary: '第 87 行：String city = order.getAddress().getCity(); —— getAddress() 可能为 null，上游第三方导入通道未做地址必填校验。', truncated: false, costMs: 120 }),
    e({ type: 'thinking', content: '再看一眼运行时状态，排除并发资源类问题。' }),
    e({ type: 'tool_call', tool: 'check_project_health', args: { projectId: 'p-001' } }),
    e({ type: 'tool_result', tool: 'check_project_health', summary: 'health=UP；DB 连接池 8/10；JVM heap 61%；线程无死锁。运行时健康，排除资源瓶颈。', truncated: false, costMs: 60 }),
    ...tokens.map((content) => e({ type: 'token', content })),
    e({
      type: 'result',
      rootCause: 'OrderService.createOrder 第 87 行调用 order.getAddress().getCity() 前未判空；该订单来自第三方导入通道，地址字段允许为空，导致空指针异常。',
      evidence: [
        'search_logs：10 分钟内 9 条相同堆栈 NPE，全部位于 OrderService.createOrder',
        'read_source_code：87 行为链式调用 order.getAddress().getCity()，无判空保护',
        'check_project_health：DB 池 8/10、heap 61%，运行时健康，排除资源问题',
        '日志模板簇 cluster_12 命中 9 次，与导入通道批量下单时间吻合',
      ],
      suggestion: '在 createOrder 入口对 address 判空，为空时回退默认收货地址并记录 WARN；同时给第三方导入通道补齐地址必填校验，把问题拦在数据入口。',
      confidence: 0.92,
    }),
  ]
  return {
    sessionId,
    projectId,
    projectName,
    status: 'RUNNING',
    triggerLog: NPE_TRIGGER_LOG,
    startedAt: dayjs().subtract(3, 'minute').toISOString(),
    finishedAt: null,
    totalCostMs: null,
    llmUsed: true,
    matchedAnalyzer: null,
    result: null,
    events,
  }
}

/** 会话 B：阶段 2 规则命中（零 LLM 成本） */
function buildRuleSession(sessionId: string, projectId = 'p-001', projectName = 'order-service 订单中心'): DiagnosisSession {
  const e = ev(sessionId)
  const events: WsMessage[] = [
    e({ type: 'stage', stage: 1, name: '日志预处理 · Drain3 模板提取', status: 'running' }),
    e({ type: 'stage', stage: 1, name: '日志预处理 · Drain3 模板提取', status: 'done', costMs: 18 }),
    e({ type: 'stage', stage: 2, name: '规则引擎预筛 · AnalyzerRegistry', status: 'running' }),
    e({ type: 'rule_hit', analyzer: 'HikariPoolTimeoutAnalyzer', solution: '连接池获取超时的常见处置：1) 检查慢 SQL 与长事务（show processlist / 慢查询日志）；2) 确认 maximumPoolSize 是否与实际并发匹配；3) 排查下游数据库对连接数的限制。近 7 天该规则已命中 23 次，均由慢 SQL 引起。', costMs: 5 }),
    e({ type: 'stage', stage: 2, name: '规则引擎预筛 · AnalyzerRegistry', status: 'done', costMs: 5 }),
    e({
      type: 'result',
      rootCause: 'HikariPool 连接获取超时（30s），匹配经验规则 HikariPoolTimeoutAnalyzer：由慢 SQL 占满连接池导致。',
      evidence: [
        '规则库匹配：cluster_23 → HikariPoolTimeoutAnalyzer（近 7 天命中 23 次）',
        '同时段慢查询日志存在 2 条 12s+ 的 ORDER BY 全表扫描',
      ],
      suggestion: '优先优化该慢 SQL 的索引；临时可将 maximumPoolSize 从 10 上调至 15 缓解，并设置 leakDetectionThreshold 暴露泄漏点。',
      confidence: 0.98,
    }),
  ]
  return {
    sessionId,
    projectId,
    projectName,
    status: 'RUNNING',
    triggerLog: HIKARI_TRIGGER_LOG,
    startedAt: dayjs().subtract(8, 'minute').toISOString(),
    finishedAt: null,
    totalCostMs: null,
    llmUsed: false,
    matchedAnalyzer: null,
    result: null,
    events,
  }
}

/** 已完成的历史会话（用于历史列表与 REST 回放） */
function completed(session: DiagnosisSession, sessionId: string): DiagnosisSession {
  const result = session.events.findLast((m) => m.event.type === 'result')
  return {
    ...session,
    sessionId,
    status: 'SUCCEEDED',
    finishedAt: dayjs().subtract(40, 'minute').toISOString(),
    totalCostMs: session.llmUsed ? 4820 : 28,
    matchedAnalyzer: session.llmUsed ? null : 'HikariPoolTimeoutAnalyzer',
    result: result?.event as never,
  }
}

const agent001 = buildAgentSession('sess-agent-001')
const rule001 = buildRuleSession('sess-rule-001')

/** 覆盖开始时间，让「组内按时间倒序」这件事在 mock 里看得见 */
function at(session: DiagnosisSession, minutesAgo: number): DiagnosisSession {
  return { ...session, startedAt: dayjs().subtract(minutesAgo, 'minute').toISOString() }
}

/**
 * 会话数据按项目分布（3 个项目共 8 条），用于验证分组视图：
 * p-001 order-service 订单中心 / p-002 user-service 用户中心 / p-003 api-gateway 网关
 */
export const DIAGNOSIS_FIXTURES: DiagnosisSession[] = [
  // p-001 订单中心：2 条进行中 + 1 条已完成
  agent001,
  rule001,
  at(completed(buildAgentSession('sess-agent-002'), 'sess-agent-002'), 12),
  // p-002 用户中心：3 条已完成（含 1 条规则命中）
  at(completed(buildAgentSession('sess-agent-003', 'p-002', 'user-service 用户中心'), 'sess-agent-003'), 26),
  at(completed(buildRuleSession('sess-rule-003', 'p-002', 'user-service 用户中心'), 'sess-rule-003'), 41),
  at(completed(buildAgentSession('sess-agent-004', 'p-002', 'user-service 用户中心'), 'sess-agent-004'), 95),
  // p-003 网关：2 条已完成
  at(completed(buildRuleSession('sess-rule-002', 'p-003', 'api-gateway 网关'), 'sess-rule-002'), 18),
  at(completed(buildAgentSession('sess-agent-005', 'p-003', 'api-gateway 网关'), 'sess-agent-005'), 150),
]

/** 用模板克隆一个"新的"运行中会话（模拟 trigger 创建） */
export function cloneSession(template: 'agent' | 'rule', sessionId: string, projectId = 'p-001', projectName = 'order-service 订单中心'): DiagnosisSession {
  const src = template === 'agent' ? agent001 : rule001
  const e = ev(sessionId)
  const events = src.events.map((m) => e(m.event))
  return {
    ...src,
    sessionId,
    projectId,
    projectName,
    events,
    startedAt: new Date().toISOString(),
  }
}
