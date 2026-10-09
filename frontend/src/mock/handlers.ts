import type { PageResult } from '@/types/common'
import type { Project, ProjectStatus } from '@/types/project'
import type { MonitorStatus } from '@/types/monitor'
import type { LogEntry, LogQuery, LogStats } from '@/types/log'
import type { DiagnosisSession } from '@/types/diagnosis'
import type { Report } from '@/types/report'
import type { HealthItem } from '@/types/common'
import type { ModelConfig } from '@/types/report'
import { db, monitorOf, tickMonitor } from './db'
import { LOG_FIXTURES, buildLogStats } from './fixtures/log'
import { cloneSession } from './fixtures/diagnosis'

type HandlerCtx = {
  params: Record<string, string>
  query: URLSearchParams
  body: any
}

type Handler = (ctx: HandlerCtx) => unknown

interface Route {
  method: string
  regex: RegExp
  keys: string[]
  handler: Handler
}

const routes: Route[] = []

function register(method: string, path: string, handler: Handler) {
  const keys: string[] = []
  const pattern = path.replace(/:([A-Za-z0-9_]+)/g, (_, key) => {
    keys.push(key)
    return '([^/]+)'
  })
  routes.push({ method, regex: new RegExp(`^${pattern}$`), keys, handler })
}

const wait = (min = 80, max = 240) =>
  new Promise((r) => setTimeout(r, min + Math.random() * (max - min)))

/* ---------------- 项目 ---------------- */
register('GET', '/api/projects', ({ query }) => {
  const page = Number(query.get('page') ?? 1)
  const size = Number(query.get('size') ?? 10)
  const status = query.get('status')
  const keyword = (query.get('keyword') ?? '').trim().toLowerCase()
  let list = [...db.projects]
  if (status && status !== 'ALL') list = list.filter((p) => p.status === status)
  if (keyword) list = list.filter((p) => p.name.toLowerCase().includes(keyword))
  return pageOf(list, page, size)
})

register('GET', '/api/projects/:id', ({ params }) => {
  return db.projects.find((p) => p.id === params.id) ?? null
})

register('POST', '/api/projects', ({ body }) => {
  const source = body.source === 'UPLOAD' ? 'UPLOAD' : 'PATH'
  const p: Project = {
    id: 'p-' + String(Date.now()).slice(-6),
    name: body.name ?? '未命名项目',
    description: body.description ?? '',
    status: body.status ?? 'DEVELOPING',
    language: body.language ?? 'Java',
    source,
    logPath: source === 'UPLOAD' ? (body.folderName ?? 'uploaded-logs') : (body.logPath ?? ''),
    fileCount: source === 'UPLOAD' ? (body.files?.length ?? 0) : null,
    healthUrl: body.healthUrl ?? null,
    sourceRoot: body.sourceRoot ?? null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    errorCount24h: source === 'UPLOAD' ? Math.floor(Math.random() * 20) : 0,
    diagnosisCount: 0,
  }
  db.projects.unshift(p)
  return p
})

register('PUT', '/api/projects/:id', ({ params, body }) => {
  const p = db.projects.find((x) => x.id === params.id)
  if (!p) return null
  Object.assign(p, body, { id: p.id, updatedAt: new Date().toISOString() })
  return p
})

register('DELETE', '/api/projects/:id', ({ params }) => {
  db.projects = db.projects.filter((p) => p.id !== params.id)
  return null
})

/* ---------------- 监听 ---------------- */
register('GET', '/api/projects/:id/monitor', ({ params }) => {
  const m = monitorOf(params.id)
  if (m.running) tickMonitor(m)
  return m
})

register('POST', '/api/projects/:id/monitor/start', ({ params }) => {
  const m = monitorOf(params.id)
  m.running = true
  m.lastReadAt = new Date().toISOString()
  return m
})

register('POST', '/api/projects/:id/monitor/stop', ({ params }) => {
  const m = monitorOf(params.id)
  m.running = false
  return m
})

/* ---------------- 日志 ---------------- */
register('GET', '/api/projects/:id/logs', ({ params, query }) => {
  const q: LogQuery = {
    keyword: query.get('keyword') ?? undefined,
    level: query.getAll('level').length ? (query.getAll('level') as LogQuery['level']) : undefined,
    clusterId: query.get('clusterId') ?? undefined,
    page: Number(query.get('page') ?? 1),
    size: Number(query.get('size') ?? 20),
  }
  let list: LogEntry[] = LOG_FIXTURES.filter((l) => l.projectId === params.id)
  if (q.keyword) list = list.filter((l) => l.rawText.toLowerCase().includes(q.keyword!.toLowerCase()))
  if (q.level?.length) list = list.filter((l) => q.level!.includes(l.level))
  if (q.clusterId) list = list.filter((l) => l.clusterId === q.clusterId)
  list = [...list].sort((a, b) => b.timestamp.localeCompare(a.timestamp))
  return pageOf(list, q.page ?? 1, q.size ?? 20)
})

register('GET', '/api/projects/:id/logs/stats', ({ params }) => buildLogStats(params.id))

register('GET', '/api/projects/:id/logs/:logId', ({ params }) => {
  return LOG_FIXTURES.find((l) => l.id === params.logId) ?? null
})

register('POST', '/api/projects/:id/logs/import', () => {
  const taskId = 'imp-' + String(Date.now()).slice(-6)
  db.importTasks.set(taskId, { total: 5, processed: 0, status: 'RUNNING' })
  return { taskId }
})

register('GET', '/api/logs/import/:taskId/progress', ({ params }) => {
  const t = db.importTasks.get(params.taskId)
  if (!t) return null
  if (t.status === 'RUNNING' && t.processed < t.total) {
    t.processed = Math.min(t.total, t.processed + 1)
    if (t.processed >= t.total) t.status = 'DONE'
  }
  return t
})

/* ---------------- 诊断 ---------------- */
register('POST', '/api/diagnosis/trigger', ({ body }) => {
  const log = LOG_FIXTURES.find((l) => l.id === body.logId)
  const project = db.projects.find((p) => p.id === (body.projectId ?? log?.projectId))
  const kind = log?.clusterId === 'cluster_23' ? 'rule' : 'agent'
  const sessionId = `sess-live-${String(Date.now()).slice(-6)}`
  db.sessions.unshift(
    cloneSession(kind, sessionId, project?.id ?? 'p-001', project?.name ?? 'order-service 订单中心'),
  )
  return { sessionId }
})

register('GET', '/api/diagnosis/sessions', ({ query }) => {
  const page = Number(query.get('page') ?? 1)
  const size = Number(query.get('size') ?? 20)
  const projectId = query.get('projectId')
  let list = [...db.sessions]
  if (projectId) list = list.filter((s) => s.projectId === projectId)
  return pageOf(list, page, size)
})

register('GET', '/api/diagnosis/:sessionId', ({ params }) => {
  const s = db.sessions.find((x) => x.sessionId === params.sessionId)
  // 终态会话原样返回（含 events，供断线补齐 / 刷新回放）
  if (s && s.status !== 'RUNNING') return s
  // 运行中会话只返回元信息 + 已落地事件（此处 mock 简化为空）
  return s ? { ...s, events: [] } : null
})

/* ---------------- 报告 ---------------- */
register('POST', '/api/reports/generate', ({ body }) => {
  const r: Report = {
    id: 'r-' + String(Date.now()).slice(-6),
    projectId: body.projectId,
    title: `复盘报告 · ${new Date().toLocaleString('zh-CN')}`,
    sessionIds: body.sessionIds ?? [],
    markdown: db.reports[0]?.markdown ?? '',
    createdAt: new Date().toISOString(),
    logCount: 47,
    diagnosisCount: body.sessionIds?.length ?? 1,
  }
  db.reports.unshift(r)
  return r
})

register('GET', '/api/reports', ({ query }) => {
  const page = Number(query.get('page') ?? 1)
  const size = Number(query.get('size') ?? 20)
  const projectId = query.get('projectId')
  let list = [...db.reports]
  if (projectId) list = list.filter((r) => r.projectId === projectId)
  return pageOf(list, page, size)
})

register('GET', '/api/reports/:id', ({ params }) => {
  return db.reports.find((r) => r.id === params.id) ?? null
})

/* ---------------- 系统 ---------------- */
register('GET', '/api/settings/health', (): HealthItem[] => [
  { name: 'MySQL', ok: true, latencyMs: 3 },
  { name: 'Redis', ok: true, latencyMs: 1 },
  { name: 'Elasticsearch', ok: true, latencyMs: 12 },
  { name: 'Python AI 服务', ok: true, latencyMs: 45 },
  { name: 'LLM · Ollama', ok: true, latencyMs: 8 },
])

register('GET', '/api/settings/model', (): ModelConfig => ({
  provider: 'ollama',
  model: 'qwen2.5:7b',
  baseUrl: 'http://127.0.0.1:11434/v1',
}))

/* ---------------- 分发器 ---------------- */
function pageOf<T>(list: T[], page: number, size: number): PageResult<T> {
  return {
    list: list.slice((page - 1) * size, page * size),
    total: list.length,
    page,
    size,
  }
}

export async function mockRequest<T>(
  method: string,
  url: string,
  body?: unknown,
  params?: Record<string, unknown>,
): Promise<T> {
  await wait()
  const [path, qs] = url.split('?')
  const query = new URLSearchParams(qs ?? '')
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (Array.isArray(v)) v.forEach((x) => query.append(k, String(x)))
      else if (v !== undefined && v !== null && v !== '') query.set(k, String(v))
    }
  }
  for (const route of routes) {
    if (route.method !== method) continue
    const m = route.regex.exec(path ?? '')
    if (!m) continue
    const paramMap: Record<string, string> = {}
    route.keys.forEach((k, i) => (paramMap[k] = m[i + 1]))
    const result = route.handler({ params: paramMap, query, body })
    if (result === null) {
      if (route.method === 'GET') throw new Error('资源不存在或已过期')
      continue
    }
    return result as T
  }
  throw new Error(`Mock 未实现的路由: ${method} ${url}`)
}
