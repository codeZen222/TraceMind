import type { ChatMessage, DiagnosisSession } from '@/types/diagnosis'

/**
 * 人机追问 mock 引擎：根据会话上下文（结论/证据/触发日志）生成关键词相关的回复。
 * 真实后端：POST /api/diagnosis/:sessionId/chat（SSE 流式，携带会话与历史消息）。
 */
export function buildFollowUpReply(session: DiagnosisSession, question: string): string {
  const q = question.toLowerCase()
  const r = session.result
  const proj = session.projectName

  if (/根因|为什么|why|cause/.test(q)) {
    return r
      ? `关于「${r.rootCause}」的推理链路是这样的：\n\n` +
          `1. 触发日志的堆栈指向具体代码位置，我先通过 search_logs 确认了同一时间窗口内相同堆栈的聚集程度；\n` +
          `2. 再用 read_source_code 读取对应源码行，验证变量来源与调用链上确实缺少判空保护；\n` +
          `3. 最后用 check_project_health 排除了运行时资源瓶颈（DB 连接池、JVM 堆、线程死锁均正常）。\n\n` +
          `三条证据互相印证后，置信度收敛到 ${(r.confidence * 100).toFixed(0)}%。简单说：这不是偶发环境问题，而是确定性的代码缺陷 + 上游数据未校验的组合。`
      : '本次会话尚未产出结论，等 Agent 推理完成后我再回答根因问题会更准确。'
  }

  if (/修复|怎么改|怎么办|fix|解决|suggestion/.test(q)) {
    return r
      ? `修复建议分两层：\n\n**紧急止血**：${r.suggestion}\n\n**长期防御**：\n` +
          `- 在该模块补齐单元测试，覆盖上游字段为空的边界用例；\n` +
          `- 给 ${proj} 的数据入口统一加 Bean Validation（@NotNull / @Valid），把非法数据拦在最外层；\n` +
          `- 考虑接入静态分析（SpotBugs / ErrorProne）在 CI 阶段捕获链式调用的 NPE 风险。\n\n` +
          `需要的话我可以把修复建议整理成 Markdown 报告，方便提工单时直接引用。`
      : '结论还没出来，稍等一下再问我修复方案。'
  }

  if (/证据|日志|evidence|相关/.test(q)) {
    return r
      ? `支撑本次结论的证据共 ${r.evidence.length} 条：\n\n` +
          r.evidence.map((e, i) => `${i + 1}. ${e}`).join('\n') +
          `\n\n这些证据的原始日志都保留了 ID，可以在「日志检索」里按 relatedLogIds 精确调出来复核。`
      : '暂无可展示的证据——诊断尚未完成。'
  }

  if (/代码|源码|第.*行|code|source/.test(q)) {
    return '关键代码位置在 OrderService.createOrder 第 87 行：\n\n' +
      '```java\nString city = order.getAddress().getCity();\n```\n\n' +
      'getAddress() 返回 null 时会直接抛 NPE。推荐改写为：\n\n' +
      '```java\nAddress address = order.getAddress();\nif (address == null) {\n    log.warn("order {} has no address, fallback to default", order.getId());\n    address = defaultAddress();\n}\nString city = address.getCity();\n```\n\n注意保留 WARN 日志——这既是排障痕迹，也是后续统计导入通道脏数据比例的数据源。'
  }

  if (/置信度|confidence|确定/.test(q)) {
    return r
      ? `当前置信度 ${(r.confidence * 100).toFixed(0)}%。影响它的因素：\n\n` +
          `- 证据链完整度：工具调用全部成功，无截断（+）；\n` +
          `- 历史案例匹配：${session.llmUsed ? 'search_similar_cases 检索到高度相似的历史案例' : '规则库近 7 天多次命中同类模板'}（+）；\n` +
          `- 不确定性来源：没有拿到生产环境当时的完整请求体，只能从日志侧推断（-）。\n\n` +
          `如果你能补充当时的请求参数或导入批次号，置信度还能再提升。`
      : '结论未产出，暂无置信度。'
  }

  if (/报告|report|导出/.test(q)) {
    return '可以，右上角「生成报告」会把本次会话的事件流、结论证据和我们的对话一起打包成 Markdown 复盘报告。如果只想要我这边的分析结论，我也可以直接在这里输出精简版——你要哪种？'
  }

  // 通用回复：总结会话状态
  return `我先把这次会话的关键信息摆一下：\n\n` +
    `- 所属项目：${proj}\n` +
    `- 触发日志：${session.triggerLog.clusterId} 模板簇（${session.triggerLog.level}）\n` +
    `- 诊断路径：${session.llmUsed ? 'LangGraph Agent 深度推理' : '规则引擎直接命中，零 LLM 成本'}\n` +
    (r ? `- 结论置信度：${(r.confidence * 100).toFixed(0)}%\n` : '') +
    `\n你想深入了解哪部分？比如根因推理链、修复方案、或者某条证据的原始日志。`
}

/** 把回复按 2~5 字切分为 token 流（模拟 SSE） */
export function splitTokens(reply: string): string[] {
  const tokens: string[] = []
  let i = 0
  while (i < reply.length) {
    const len = 2 + Math.floor(Math.random() * 4)
    tokens.push(reply.slice(i, i + len))
    i += len
  }
  return tokens
}

/** 从历史消息里推断用户意图（供回复引擎后续扩展用） */
export function chatContextOf(history: ChatMessage[]): string {
  return history
    .filter((m) => m.role === 'user')
    .slice(-3)
    .map((m) => m.content)
    .join(' | ')
}
