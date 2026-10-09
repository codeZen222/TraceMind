import { get, post } from './request'
import type { PageResult } from '@/types/common'
import type { Report } from '@/types/report'

export function generateReport(projectId: string, sessionIds: string[]) {
  return post<Report>('/api/reports/generate', { projectId, sessionIds })
}

export function getReports(query: { projectId?: string; page?: number; size?: number }) {
  return get<PageResult<Report>>('/api/reports', query)
}

export function getReport(id: string) {
  return get<Report>(`/api/reports/${id}`)
}

/** 真实环境返回 text/markdown 附件；mock 下前端侧直接生成 Blob */
export async function downloadReportMarkdown(report: Report) {
  const blob = new Blob([report.markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${report.title}.md`
  a.click()
  URL.revokeObjectURL(url)
}
