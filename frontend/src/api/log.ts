import { get, post } from './request'
import type { PageResult } from '@/types/common'
import type { LogEntry, LogStats, ImportProgress, NewError } from '@/types/log'

export function getLogs(projectId: string, query: Record<string, unknown>) {
  return get<PageResult<LogEntry>>(`/api/projects/${projectId}/logs`, query)
}

export function getLogStats(projectId: string) {
  return get<LogStats>(`/api/projects/${projectId}/logs/stats`)
}

export function getLogDetail(projectId: string, logId: string) {
  return get<LogEntry>(`/api/projects/${projectId}/logs/${logId}`)
}

/**
 * 上传历史日志（多文件，multipart/form-data）。
 *
 * 后端契约（接口开发手册 3.4）：
 *   POST /api/projects/{id}/logs/import
 *   Content-Type: multipart/form-data
 *   字段名固定为 `files`，每个文件一个同名 part
 *   响应 { taskId }，立刻返回，不等导入完成
 *
 * 注意必须走 FormData：直接 `post(url)` 不带 body 的话，
 * 后端 multipart 解析器拿不到任何文件，会直接判 40000「无文件」。
 */
export function importLogs(projectId: string, files: File[]) {
  const form = new FormData()
  for (const file of files) {
    // 第三个参数显式给文件名：不传的话某些浏览器会退化成 "blob"
    form.append('files', file, file.name)
  }
  return post<{ taskId: string }>(`/api/projects/${projectId}/logs/import`, form)
}

export function getImportProgress(taskId: string) {
  return get<ImportProgress>(`/api/logs/import/${taskId}/progress`)
}

/** 新错误雷达：未读的新错误列表 */
export function getNewErrors(projectId: string) {
  return get<NewError[]>(`/api/projects/${projectId}/errors/new`)
}

/** 新错误雷达：标记已读 */
export function ackNewError(projectId: string, clusterId: string) {
  return post<void>(`/api/projects/${projectId}/errors/${clusterId}/ack`)
}
