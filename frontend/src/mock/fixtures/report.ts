import type { Report } from '@/types/report'

const MARKDOWN = `# 订单中心 · 故障复盘报告

> 生成时间：2026-09-11 19:02 · 纳入日志 47 条 · 诊断会话 3 次

## 一、故障概览

近 24 小时 order-service 共检出 ERROR 日志 47 条，其中两个模板簇占比超过 90%：
\`cluster_12\`（NPE，9 条）与 \`cluster_23\`（Hikari 超时，38 条）。

| 模板簇 | 级别 | 条数 | 处置路径 |
|--------|------|------|----------|
| cluster_23 | ERROR | 38 | 规则命中 · 零 LLM 成本 |
| cluster_12 | ERROR | 9 | LangGraph Agent 深度诊断 |
| cluster_42 | ERROR | 3 | 规则命中 |

## 二、根因分析

### 2.1 cluster_12 · NullPointerException

Agent 综合三个工具的观测结论：

1. \`search_logs\`：10 分钟内 9 条相同堆栈，全部位于 \`OrderService.createOrder\`
2. \`read_source_code\`：第 87 行链式调用 \`order.getAddress().getCity()\` 无判空
3. \`check_project_health\`：DB 池 8/10、heap 61%，运行时健康

**结论**：第三方导入通道未校验地址必填，导致空指针。

### 2.2 cluster_23 · Hikari 连接超时

规则 \`HikariPoolTimeoutAnalyzer\` 命中（近 7 天 23 次），同时段慢查询日志存在
2 条 12s+ 的 \`ORDER BY\` 全表扫描，连接池被慢 SQL 占满。

## 三、修复建议

\`\`\`java
// OrderService.java — createOrder 入口增加判空回退
Address address = Optional.ofNullable(order.getAddress())
        .orElseGet(() -> defaultAddressRepository.forUser(order.getUserId()));
if (address == null) {
    log.warn("order {} missing address, fallback failed", order.getId());
    throw new BizException(ErrorCode.ADDRESS_REQUIRED);
}
\`\`\`

- [ ] 导入通道增加地址必填校验（拦截在入口）
- [ ] 为 ORDER BY 涉及字段补联合索引
- [ ] 临时上调 maximumPoolSize 至 15，开启 leakDetectionThreshold

## 四、后续跟踪

- 观察修复上线后 cluster_12 / cluster_23 的频次趋势
- 若规则命中持续走高，考虑沉淀为新的 Analyzer 规则
`

export const REPORT_FIXTURES: Report[] = [
  {
    id: 'r-001',
    projectId: 'p-001',
    title: '订单中心 · 近 24h NPE 与连接池超时复盘',
    sessionIds: ['sess-agent-002', 'sess-rule-002', 'sess-agent-003'],
    markdown: MARKDOWN,
    createdAt: '2026-09-11T19:02:00+08:00',
    logCount: 47,
    diagnosisCount: 3,
  },
]
