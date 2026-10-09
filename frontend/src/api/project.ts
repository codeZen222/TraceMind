import { get, post, put, del } from './request'
import type { PageResult } from '@/types/common'
import type { Project, ProjectStatus, ProjectForm } from '@/types/project'

export function getProjects(query: { page?: number; size?: number; status?: ProjectStatus | 'ALL'; keyword?: string }) {
  return get<PageResult<Project>>('/api/projects', query as Record<string, unknown>)
}

export function getProject(id: string) {
  return get<Project>(`/api/projects/${id}`)
}

export function createProject(form: ProjectForm) {
  return post<Project>('/api/projects', form)
}

export function updateProject(id: string, form: ProjectForm) {
  return put<Project>(`/api/projects/${id}`, form)
}

export function deleteProject(id: string) {
  return del<void>(`/api/projects/${id}`)
}
