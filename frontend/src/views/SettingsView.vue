<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import { getSystemHealth, getModelConfig, getAutoDiagnosis, setAutoDiagnosis } from '@/api/settings'
import { useThemeStore } from '@/stores/theme'
import type { HealthItem } from '@/types/common'
import type { ModelConfig } from '@/types/report'

const theme = useThemeStore()

/**
 * 是否跑在 Mock 模式下。
 *
 * ★ 这一页原来有一段写死的文案说「当前前后端尚未联通，数据来自 Mock 层」——
 *   它没有判断这个开关，所以哪怕 VITE_USE_MOCK=false、数据全是真实后端来的，
 *   页面上依然挂着"尚未联通"。等于系统自己声明自己是假的，是整站最伤的一处。
 *   下面两个区块按这个开关二选一。
 */
const isMock = import.meta.env.VITE_USE_MOCK === 'true'
const apiBase = import.meta.env.VITE_API_BASE || '/api'
const wsBase = import.meta.env.VITE_WS_BASE || '/ws'

/** 动态引入 mock 层：非 Mock 模式下它不该出现在主包里 */
async function resetDemoData() {
  const { resetMockDb } = await import('@/mock/db')
  resetMockDb()
}

const loading = ref(true)
const health = ref<HealthItem[]>([])
const model = ref<ModelConfig | null>(null)
const autoDiag = ref<{ enabled: boolean; limit: number } | null>(null)
const togglingAutoDiag = ref(false)

async function load() {
  loading.value = true
  try {
    const [h, m, a] = await Promise.all([getSystemHealth(), getModelConfig(), getAutoDiagnosis()])
    health.value = h
    model.value = m
    autoDiag.value = a
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function toggleAutoDiag(v: boolean) {
  if (togglingAutoDiag.value) return
  togglingAutoDiag.value = true
  try {
    await setAutoDiagnosis(v)
    if (autoDiag.value) autoDiag.value.enabled = v
    ElMessage.success(v ? '已开启自动诊断' : '已关闭自动诊断')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    togglingAutoDiag.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="系统设置" subtitle="主题、模型配置与依赖健康状态" />

    <DataState :loading="loading" :retry="load">
      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <!-- 主题 -->
        <div class="rounded-lg border border-border bg-surface p-4">
          <div class="mb-3 text-[13.5px] font-medium">外观</div>
          <div class="flex items-center gap-3">
            <el-radio-group :model-value="theme.current" @update:model-value="theme.toggle()">
              <el-radio-button value="dark">深色</el-radio-button>
              <el-radio-button value="light">浅色</el-radio-button>
            </el-radio-group>
            <span class="text-[12px] text-text-3">偏好保存在 localStorage，跟随 prefers-color-scheme 初始化</span>
          </div>
        </div>

        <!-- 模型配置 -->
        <div class="rounded-lg border border-border bg-surface p-4">
          <div class="mb-3 text-[13.5px] font-medium">模型配置</div>
          <dl v-if="model" class="space-y-2 text-[13px]">
            <div class="flex justify-between"><dt class="text-text-3">Provider</dt><dd class="mono">{{ model.provider }}</dd></div>
            <div class="flex justify-between"><dt class="text-text-3">模型</dt><dd class="mono text-accent">{{ model.model }}</dd></div>
            <div class="flex justify-between"><dt class="text-text-3">Base URL</dt><dd class="mono">{{ model.baseUrl }}</dd></div>
          </dl>
          <p class="mt-3 text-[12px] text-text-3">通过 OpenAI 兼容接口调用，可切换 Ollama 本地 / 云端模型</p>
        </div>

        <!-- 自动诊断 -->
        <div class="rounded-lg border border-border bg-surface p-4">
          <div class="mb-3 text-[13.5px] font-medium">自动诊断</div>
          <div class="flex items-center gap-3">
            <el-switch
              :model-value="autoDiag?.enabled ?? false"
              :loading="togglingAutoDiag"
              @change="toggleAutoDiag"
            />
            <span class="text-[12px] text-text-2">
              新错误雷达发现新错误时，自动触发诊断并生成报告
              <span v-if="autoDiag" class="text-text-3">（每次最多 {{ autoDiag.limit }} 个）</span>
            </span>
          </div>
        </div>

        <!-- 数据源：Mock 模式才谈"演示数据"，真实模式如实说明数据从哪来 -->
        <div v-if="isMock" class="rounded-lg border border-border bg-surface p-4 lg:col-span-2">
          <div class="mb-2 flex items-center gap-2">
            <span class="text-[13.5px] font-medium">演示数据</span>
            <span class="rounded bg-[var(--tm-code-bg)] px-1.5 py-0.5 text-[10.5px] text-text-3">Mock 模式</span>
          </div>
          <p class="text-[12.5px] text-text-2">
            当前前后端尚未联通，所有数据来自前端 Mock 层：3 个示例项目（订单中心 / 用户中心 / 网关）及其日志、诊断会话与报告。
            你新建或上传的项目保存在浏览器 localStorage，刷新页面不会丢失。
          </p>
          <el-button class="mt-3" size="small" @click="resetDemoData">恢复出厂演示数据</el-button>
          <span class="ml-2 text-[11.5px] text-text-3">清空本地修改（含新建/删除的项目），刷新后回到 3 个示例项目</span>
        </div>

        <div v-else class="rounded-lg border border-border bg-surface p-4 lg:col-span-2">
          <div class="mb-2 flex items-center gap-2">
            <span class="text-[13.5px] font-medium">数据源</span>
            <span
              class="rounded px-1.5 py-0.5 text-[10.5px]"
              style="background: var(--tm-accent-soft); color: var(--tm-accent)"
            >
              已连接后端
            </span>
          </div>
          <p class="text-[12.5px] text-text-2">
            REST 请求走 <span class="mono">{{ apiBase }}</span>，实时诊断事件走 WebSocket
            <span class="mono">{{ wsBase }}</span>；日志正文存 Elasticsearch，项目 / 诊断会话 / 报告存
            MySQL，模板提取与规则匹配在 Python AI 服务。
          </p>
          <p class="mt-2 text-[11.5px] text-text-3">
            数据不在浏览器里，换一台机器打开看到的是同一份。
          </p>
        </div>

        <!-- 依赖健康 -->
        <div class="rounded-lg border border-border bg-surface p-4 lg:col-span-2">
          <div class="mb-3 text-[13.5px] font-medium">依赖健康</div>
          <div class="grid grid-cols-1 gap-2.5 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="h in health"
              :key="h.name"
              class="flex items-center gap-3 rounded-sm border px-3.5 py-2.5"
              :style="{ borderColor: h.ok ? 'var(--tm-border)' : 'var(--tm-status-failed)' }"
              :title="h.ok ? `${h.name} 正常 · ${h.latencyMs}ms` : `${h.name} 连接失败`"
            >
              <span class="h-2 w-2 shrink-0 rounded-full" :style="{ background: h.ok ? 'var(--tm-status-success)' : 'var(--tm-status-failed)' }" />
              <span class="text-[13px] text-text-1">{{ h.name }}</span>
              <span class="mono ml-auto text-[12px]" :style="{ color: h.ok ? 'var(--tm-text-3)' : 'var(--tm-status-failed)' }">
                {{ h.ok ? `${h.latencyMs}ms` : '失败' }}
              </span>
            </div>
          </div>
          <p class="mt-3 text-[12px] text-text-3">Python AI 服务不可用时，诊断流水线自动降级为仅返回规则结果（DEGRADED）</p>
        </div>
      </div>
    </DataState>
  </div>
</template>
