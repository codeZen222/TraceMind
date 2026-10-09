import type { Project } from '@/types/project'
import type { MonitorStatus } from '@/types/monitor'
import type { DiagnosisSession } from '@/types/diagnosis'
import type { Report } from '@/types/report'
import type { ImportProgress } from '@/types/log'
import { PROJECT_FIXTURES } from './fixtures/project'
import { DIAGNOSIS_FIXTURES } from './fixtures/diagnosis'
import { REPORT_FIXTURES } from './fixtures/report'

/** Mock 层可变数据库：CRUD 操作直接改这里 */
export const db = {
  projects: [...PROJECT_FIXTURES] as Project[],
  sessions: [...DIAGNOSIS_FIXTURES] as DiagnosisSession[],
  reports: [...REPORT_FIXTURES] as Report[],
  monitor: new Map<string, MonitorStatus>(),
  importTasks: new Map<string, ImportProgress>(),
}

/* ---------------- localStorage 持久化 ----------------
 * 让「新建 / 上传 / 删除项目」等操作在刷新页面后仍然保留，
 * 避免演示时出现"刚才建的项目不见了"的困惑。
 * resetMockDb() 可随时恢复到出厂演示数据。 */
const STORAGE_KEY = 'tm-mock-db-v1'

function hydrate() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw) as {
      projects?: Project[]
      sessions?: DiagnosisSession[]
      reports?: Report[]
    }
    if (Array.isArray(saved.projects)) db.projects = saved.projects
    if (Array.isArray(saved.sessions)) db.sessions = saved.sessions
    if (Array.isArray(saved.reports)) db.reports = saved.reports
  } catch {
    /* 数据损坏则忽略，使用出厂 fixtures */
  }
}

function persist() {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ projects: db.projects, sessions: db.sessions, reports: db.reports }),
    )
  } catch {
    /* 存储已满等异常不影响运行 */
  }
}

/** 清空本地持久化的 mock 数据并刷新，恢复出厂演示数据 */
export function resetMockDb() {
  localStorage.removeItem(STORAGE_KEY)
  location.reload()
}

if (typeof window !== 'undefined') {
  hydrate()
  setInterval(persist, 2000)
}

export function monitorOf(projectId: string): MonitorStatus {
  let m = db.monitor.get(projectId)
  if (!m) {
    const p = db.projects.find((x) => x.id === projectId)
    m = {
      taskId: `task-${projectId}`,
      projectId,
      running: false,
      filePath: p?.logPath ?? '/var/log/app.log',
      offset: 0,
      fileSize: 1024 * 1024 * 42,
      lastReadAt: null,
      linesRead: 0,
      errorsDetected: 0,
      ruleMatched: 0,
      agentTriggered: 0,
    }
    db.monitor.set(projectId, m)
  }
  return m
}

/** 轮询时模拟指标自然增长 */
export function tickMonitor(m: MonitorStatus) {
  m.offset += 320 + Math.floor(Math.random() * 400)
  m.linesRead += 12 + Math.floor(Math.random() * 8)
  if (Math.random() < 0.4) m.errorsDetected += 1
  if (Math.random() < 0.7) m.ruleMatched += 1
  if (Math.random() < 0.25) m.agentTriggered += 1
  m.lastReadAt = new Date().toISOString()
}
