import { get, post } from './request'
import type { MonitorStatus } from '@/types/monitor'

export function getMonitor(projectId: string) {
  return get<MonitorStatus>(`/api/projects/${projectId}/monitor`)
}

export function startMonitor(projectId: string) {
  return post<MonitorStatus>(`/api/projects/${projectId}/monitor/start`)
}

export function stopMonitor(projectId: string) {
  return post<MonitorStatus>(`/api/projects/${projectId}/monitor/stop`)
}
