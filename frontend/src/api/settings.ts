import { get, put } from './request'
import type { HealthItem } from '@/types/common'
import type { ModelConfig } from '@/types/report'

export function getSystemHealth() {
  return get<HealthItem[]>('/api/settings/health')
}

export function getModelConfig() {
  return get<ModelConfig>('/api/settings/model')
}

/** 自动诊断开关状态 */
export function getAutoDiagnosis() {
  return get<{ enabled: boolean; limit: number }>('/api/settings/auto-diagnosis')
}

/** 切换自动诊断开关 */
export function setAutoDiagnosis(enabled: boolean) {
  return put<void>('/api/settings/auto-diagnosis', { enabled })
}
