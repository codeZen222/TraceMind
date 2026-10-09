<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import LevelTag from '@/components/common/LevelTag.vue'
import { getProject } from '@/api/project'
import { getMonitor, startMonitor, stopMonitor } from '@/api/monitor'
import { getLogs } from '@/api/log'
import type { Project } from '@/types/project'
import type { MonitorStatus } from '@/types/monitor'
import type { LogEntry, LogLevel } from '@/types/log'
import { useLogFormat } from '@/composables/useLogFormat'

const route = useRoute()
const projectId = computed(() => String(route.params.id ?? ''))
const { rel, abs, bytes, cost } = useLogFormat()

const project = ref<Project | null>(null)
const monitor = ref<MonitorStatus | null>(null)
const metaLoading = ref(true)
const toggling = ref(false)
const error = ref<string | null>(null)

/* 3s 轮询（页面隐藏时暂停，离开路由清理）：一次 tick 同时刷监听指标 + 日志尾部 */
let timer: ReturnType<typeof setInterval> | null = null

function startTimer() {
  if (timer) return
  timer = setInterval(() => {
    void poll()
    void loadTail()
  }, 3000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function loadMeta() {
  metaLoading.value = true
  try {
    project.value = await getProject(projectId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '项目不存在'
  } finally {
    metaLoading.value = false
  }
}

async function poll() {
  try {
    monitor.value = await getMonitor(projectId.value)
  } catch {
    /* 单次轮询失败不打断 */
  }
}

function onVisibility() {
  if (document.visibilityState === 'visible' && monitor.value?.running) {
    startTimer()
  } else if (document.visibilityState === 'hidden') {
    stopTimer()
  }
}

onMounted(async () => {
  await loadMeta()
  await Promise.all([poll(), loadTail()])
  if (monitor.value?.running) startTimer()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stopTimer()
  document.removeEventListener('visibilitychange', onVisibility)
})

async function toggle() {
  toggling.value = true
  try {
    monitor.value = await (monitor.value?.running ? stopMonitor(projectId.value) : startMonitor(projectId.value))
    if (monitor.value.running) {
      ElMessage.success('监听已启动')
      startTimer()
    } else {
      ElMessage.info('监听已停止')
      stopTimer()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    toggling.value = false
  }
}

/*
 * 实时日志尾部（tail -f 效果）。
 *
 * ★ 之前这里是 `LOG_FIXTURES.slice(0, 60)` + 随机时间戳 —— 页面上日志看着在滚，
 *   其实一条真实数据都没有，纯装饰。现在改成真读 ES：复用日志检索接口
 *   （后端固定按 timestamp desc 排序），每次取最新 60 条，展示时翻成「旧 → 新」，
 *   视觉上才等价于 tail -f。
 */
const TAIL_SIZE = 60
const tailEl = ref<HTMLElement | null>(null)
const autoScroll = ref(true)
const tailLogs = ref<LogEntry[]>([])
const tailLoading = ref(true)

async function loadTail() {
  try {
    const res = await getLogs(projectId.value, { page: 1, size: TAIL_SIZE })
    tailLogs.value = [...res.list].reverse()
  } catch {
    /* 尾部是辅助信息，单次拉取失败不打断整页 */
  } finally {
    tailLoading.value = false
  }
}

/* 只在用户停在底部时自动贴底；往上翻了就别抢他的滚动条 */
watch(
  () => tailLogs.value[tailLogs.value.length - 1]?.id,
  async () => {
    if (!autoScroll.value) return
    await nextTick()
    const el = tailEl.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

function backToTail() {
  autoScroll.value = true
  const el = tailEl.value
  if (el) el.scrollTop = el.scrollHeight
}

function levelBorder(level: LogLevel) {
  return level === 'ERROR'
    ? { borderLeft: '3px solid var(--tm-level-error)', background: 'rgba(229, 83, 75, 0.06)' }
    : level === 'WARN'
      ? { borderLeft: '3px solid var(--tm-level-warn)' }
      : { borderLeft: '3px solid transparent' }
}

function onTailScroll() {
  const el = tailEl.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

/* 诊断路径占比 */
const ruleRate = computed(() => {
  const m = monitor.value
  if (!m || m.ruleMatched + m.agentTriggered === 0) return 0
  return Math.round((m.ruleMatched / (m.ruleMatched + m.agentTriggered)) * 100)
})
</script>

<template>
  <div>
    <DataState :loading="metaLoading" :error="error" :retry="loadMeta">
      <template v-if="project">
        <PageHeader :title="project.name" :subtitle="project.description">
          <template #actions>
            <el-tag size="small" :type="project.status === 'DEVELOPING' ? 'warning' : 'success'" effect="plain" round class="mr-1">
              {{ project.status === 'DEVELOPING' ? '开发中' : '已上线' }}
            </el-tag>
            <el-button
              :type="monitor?.running ? 'danger' : 'primary'"
              :loading="toggling"
              @click="toggle"
            >
              {{ monitor?.running ? '停止监听' : '启动监听' }}
            </el-button>
          </template>
        </PageHeader>

        <!-- 监听开关区 -->
        <div class="flex flex-wrap items-center gap-x-8 gap-y-3 rounded-lg border border-border bg-surface p-4">
          <div class="flex items-center gap-2.5">
            <span
              class="h-2.5 w-2.5 rounded-full"
              :style="{
                background: monitor?.running ? 'var(--tm-status-running)' : 'var(--tm-text-3)',
                animation: monitor?.running ? 'breathe 1.8s ease-in-out infinite' : undefined,
              }"
            />
            <span class="text-[14px] font-medium">{{ monitor?.running ? '运行中' : '已停止' }}</span>
          </div>
          <div class="mono text-[12px] text-text-2">{{ monitor?.filePath }}</div>
          <div class="mono text-[12px] text-text-3">offset {{ monitor?.offset?.toLocaleString() }}</div>
          <div class="mono text-[12px] text-text-3">文件 {{ monitor ? bytes(monitor.fileSize) : '--' }}</div>
          <div class="mono text-[12px] text-text-3">
            最后读取 {{ monitor?.lastReadAt ? rel(monitor.lastReadAt) : '--' }}
          </div>
        </div>

        <!-- 实时指标 -->
        <div class="mt-4 grid grid-cols-2 gap-3 md:grid-cols-4">
          <div
            v-for="c in [
              { label: '累计读取行数', v: monitor?.linesRead.toLocaleString() ?? '--' },
              { label: '检出 ERROR', v: monitor?.errorsDetected ?? '--' },
              { label: '规则拦截', v: monitor?.ruleMatched ?? '--' },
              { label: '触发 Agent', v: monitor?.agentTriggered ?? '--' },
            ]"
            :key="c.label"
            class="rounded-lg border border-border bg-surface px-4 py-3"
          >
            <div class="text-[12px] text-text-3">{{ c.label }}</div>
            <div class="mono mt-1 text-[20px] font-semibold">{{ c.v }}</div>
          </div>
        </div>

        <!-- 诊断路径占比 -->
        <div class="mt-4 rounded-lg border border-border bg-surface p-4">
          <div class="mb-2 flex items-center justify-between text-[13px]">
            <span class="font-medium">诊断路径占比</span>
            <span class="mono text-text-3">
              <span style="color: var(--tm-stage-2)">规则拦截 {{ monitor?.ruleMatched ?? 0 }}</span>
              ·
              <span style="color: var(--tm-stage-3)">Agent {{ monitor?.agentTriggered ?? 0 }}</span>
            </span>
          </div>
          <div class="flex h-2.5 overflow-hidden rounded-full bg-[var(--tm-code-bg)]">
            <div class="h-full transition-all duration-500" :style="{ width: `${ruleRate}%`, background: 'var(--tm-stage-2)' }" />
            <div class="h-full flex-1 transition-all duration-500" style="background: var(--tm-stage-3)" />
          </div>
          <div class="mono mt-1.5 text-[11.5px] text-text-3">
            {{ ruleRate }}% 的已知异常被规则毫秒级拦截，未烧任何 token
          </div>
        </div>

        <!-- 实时日志尾部 -->
        <div class="mt-4 overflow-hidden rounded-lg border border-border bg-surface">
          <div class="flex items-center justify-between border-b border-border px-4 py-2.5 text-[13px] font-medium">
            <div>
              实时日志尾部
              <span class="mono ml-2 text-[11px] text-text-3">tail -f {{ project.logPath }}</span>
            </div>
            <div class="flex items-center gap-2">
              <span class="mono text-[11px] text-text-3">
                最近 {{ tailLogs.length }} 条{{ monitor?.running ? ' · 每 3s 刷新' : '' }}
              </span>
              <el-button v-if="!autoScroll" link size="small" @click="backToTail">回到最新</el-button>
            </div>
          </div>
          <div
            ref="tailEl"
            class="mono space-y-px overflow-y-auto bg-[var(--tm-code-bg)] p-2"
            style="max-height: 340px"
            @scroll="onTailScroll"
          >
            <div v-if="!tailLogs.length" class="px-3 py-8 text-center text-[12px] text-text-3">
              {{ tailLoading ? '加载中…' : `还没有日志——启动监听后，写入 ${project.logPath} 的内容会出现在这里` }}
            </div>
            <div
              v-for="l in tailLogs"
              :key="l.id"
              class="flex items-baseline gap-2 rounded-sm px-2 py-1 text-[12px] transition-opacity duration-200"
              :style="levelBorder(l.level)"
            >
              <span class="shrink-0 text-text-3">{{ new Date(l.timestamp).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
              <LevelTag :level="l.level" />
              <span class="min-w-0 flex-1 truncate text-text-2">{{ l.rawText }}</span>
            </div>
          </div>
        </div>
      </template>
    </DataState>
  </div>
</template>

<style scoped>
@keyframes breathe {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
</style>
