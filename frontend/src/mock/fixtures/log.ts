import dayjs from 'dayjs'
import type { LogEntry, LogLevel, LogStats } from '@/types/log'

/** 模板簇定义（Drain3 输出形态） */
const TEMPLATES = [
  { clusterId: 'cluster_01', level: 'WARN'  as LogLevel, template: 'User * login failed from *', raw: (i: number) => `User admin${i % 30} login failed from 192.168.1.${20 + (i % 30)}` },
  { clusterId: 'cluster_07', level: 'ERROR' as LogLevel, template: 'Connection refused: *', raw: () => 'Connection refused: order-db-read.hbase:2181' },
  { clusterId: 'cluster_12', level: 'ERROR' as LogLevel, template: 'NullPointerException at *', raw: () => 'java.lang.NullPointerException: Cannot invoke "Address.getCity()" because the return value of "Order.getAddress()" is null' },
  { clusterId: 'cluster_23', level: 'ERROR' as LogLevel, template: 'HikariPool-1 - Timeout failure: *', raw: () => 'HikariPool-1 - Timeout failure: connection was not obtained from pool within 30000ms.' },
  { clusterId: 'cluster_42', level: 'ERROR' as LogLevel, template: 'Redis command timed out after * ms', raw: () => 'Redis command timed out after 5000ms; nested exception is io.lettuce.core.RedisCommandTimeoutException' },
  { clusterId: 'cluster_55', level: 'INFO'  as LogLevel, template: 'Order * created successfully', raw: (i: number) => `Order ORD-2026${String(1000 + i)} created successfully` },
  { clusterId: 'cluster_61', level: 'DEBUG' as LogLevel, template: 'Resolved route * -> *', raw: (i: number) => `Resolved route ${i % 7} -> handler#method${i % 5}` },
  /* user-service（p-002）专属模板簇 */
  { clusterId: 'cluster_08', level: 'WARN'  as LogLevel, template: 'Token refresh failed for user *', raw: (i: number) => `Token refresh failed for user u-${1000 + i}: expired before refresh` },
  { clusterId: 'cluster_09', level: 'INFO'  as LogLevel, template: 'JWT * issued for user *', raw: (i: number) => `JWT eyJhbGciOiJIUzI1NiJ${i % 9} issued for user u-${1000 + i}` },
  { clusterId: 'cluster_10', level: 'ERROR' as LogLevel, template: 'Login throttled: too many attempts from *', raw: (i: number) => `Login throttled: too many attempts from 10.3.${i % 8}.${i % 200} within 60s` },
  /* api-gateway（p-003）专属模板簇 */
  { clusterId: 'cluster_11', level: 'WARN'  as LogLevel, template: 'Rate limit exceeded for client *', raw: (i: number) => `Rate limit exceeded for client 10.2.3.${i % 20}: limit 100 req/s` },
  { clusterId: 'cluster_13', level: 'ERROR' as LogLevel, template: 'Upstream timeout after * ms', raw: () => 'Upstream timeout after 30000 ms while calling user-service /api/users/me' },
  { clusterId: 'cluster_14', level: 'INFO'  as LogLevel, template: 'Health check passed for route *', raw: (i: number) => `Health check passed for route ${i % 4} -> upstream ${i % 3}` },
]

const STACKS: Record<string, string> = {
  cluster_12: [
    'java.lang.NullPointerException: Cannot invoke "Address.getCity()" because the return value of "Order.getAddress()" is null',
    '\tat com.tracemind.orders.service.OrderService.createOrder(OrderService.java:87)',
    '\tat com.tracemind.orders.controller.OrderController.create(OrderController.java:44)',
    '\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)',
    '\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1072)',
  ].join('\n'),
  cluster_23: [
    'java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.',
    '\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:695)',
    '\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)',
    '\tat org.hibernate.engine.jdbc.internal.JdbcServicesImpl$ConnectionProviderObtainerImpl.getConnection(JdbcServicesImpl.java:118)',
  ].join('\n'),
}

/** 每个项目的日志数据集规格：p-001 保持原量（含 NPE / Hikari 触发日志），其余项目补齐可检索的日志 */
const PROJECT_SPECS: { projectId: string; filePath: string; count: number; idPrefix: string; offsetMin: number; ids: string[] }[] = [
  { projectId: 'p-001', filePath: '/var/log/order-service/app.log', count: 86, idPrefix: 'log', offsetMin: 0, ids: ['cluster_01', 'cluster_07', 'cluster_12', 'cluster_23', 'cluster_42', 'cluster_55', 'cluster_61'] },
  { projectId: 'p-002', filePath: '/data/logs/user-service/app.log', count: 48, idPrefix: 'log2', offsetMin: 3, ids: ['cluster_01', 'cluster_42', 'cluster_08', 'cluster_09', 'cluster_10'] },
  { projectId: 'p-003', filePath: '/data/logs/gateway/gateway.log', count: 42, idPrefix: 'log3', offsetMin: 5, ids: ['cluster_61', 'cluster_07', 'cluster_11', 'cluster_13', 'cluster_14'] },
]

/** 生成确定性的日志条目（三个项目各有独立数据集） */
function buildLogs(): LogEntry[] {
  const now = dayjs()
  const list: LogEntry[] = []
  for (const spec of PROJECT_SPECS) {
    const pool = TEMPLATES.filter((t) => spec.ids.includes(t.clusterId))
    for (let i = 0; i < spec.count; i++) {
      const base = pool[i % pool.length]
      // p-001：cluster_12 与 cluster_23 是本次故障的主角，多来几条（与原数据集分布一致）
      const tpl =
        spec.projectId === 'p-001' && (i % 5 === 0 || i % 5 === 3)
          ? (i % 10 < 5 ? TEMPLATES[2] : TEMPLATES[3])
          : base
      list.push({
        id: `${spec.idPrefix}-${String(i + 1).padStart(4, '0')}`,
        projectId: spec.projectId,
        timestamp: now.subtract(i * 17 + spec.offsetMin, 'minute').toISOString(),
        level: tpl.level,
        rawText: tpl.raw(i),
        template: tpl.template,
        clusterId: tpl.clusterId,
        filePath: spec.filePath,
        lineNumber: 1000 + i * 7,
        stackTrace: STACKS[tpl.clusterId] ?? null,
      })
    }
  }
  return list
}

export const LOG_FIXTURES: LogEntry[] = buildLogs()

/** NPE 触发日志（Agent 会话用） */
export const NPE_TRIGGER_LOG: LogEntry = LOG_FIXTURES.find(
  (l) => l.clusterId === 'cluster_12',
) as LogEntry

/** Hikari 超时触发日志（规则命中会话用） */
export const HIKARI_TRIGGER_LOG: LogEntry = LOG_FIXTURES.find(
  (l) => l.clusterId === 'cluster_23',
) as LogEntry

export function buildLogStats(projectId = 'p-001'): LogStats {
  const now = dayjs()
  const logs = LOG_FIXTURES.filter((l) => l.projectId === projectId)
  const levelDistribution = (['ERROR', 'WARN', 'INFO', 'DEBUG'] as LogLevel[]).map((level) => ({
    level,
    count: logs.filter((l) => l.level === level).length,
  }))
  const seed = (projectId.charCodeAt(projectId.length - 1) % 5) + 1
  const frequencyTrend = Array.from({ length: 24 }, (_, h) => ({
    time: now.subtract(23 - h, 'hour').format('HH:00'),
    count: h === 21 ? 9 + seed : h === 22 ? 14 : h === 23 ? 11 : (h * 7 + seed) % 6,
  }))
  const counts = new Map<string, { count: number; level: LogLevel; template: string }>()
  for (const l of logs) {
    const cur = counts.get(l.clusterId)
    if (cur) cur.count++
    else counts.set(l.clusterId, { count: 1, level: l.level, template: l.template })
  }
  const topTemplates = [...counts.entries()]
    .map(([clusterId, v]) => ({ clusterId, ...v }))
    .sort((a, b) => b.count - a.count)
  return { levelDistribution, frequencyTrend, topTemplates }
}
