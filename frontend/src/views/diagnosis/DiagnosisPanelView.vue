<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDiagnosisStore } from '@/stores/diagnosis'
import { getDiagnosisSessions, getDiagnosisSession } from '@/api/diagnosis'
import { getProjects } from '@/api/project'
import type { Project } from '@/types/project'
import type { DiagnosisSession, WsMessage } from '@/types/diagnosis'
import StagePipeline, { type StageState } from '@/components/diagnosis/StagePipeline.vue'
import EventStream from '@/components/diagnosis/EventStream.vue'
import ConclusionCard from '@/components/diagnosis/ConclusionCard.vue'
import SessionList from '@/components/diagnosis/SessionList.vue'
import ChatPanel from '@/components/diagnosis/ChatPanel.vue'
import ErrorLogBanner from '@/components/diagnosis/ErrorLogBanner.vue'
import DataState from '@/components/common/DataState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useLogFormat } from '@/composables/useLogFormat'
import { projectColor } from '@/utils/projectColor'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const store = useDiagnosisStore()
const { rel, cost } = useLogFormat()

/* -------- 会话列表（左栏）+ 项目筛选 -------- */
const sessions = ref<DiagnosisSession[]>([])
const listLoading = ref(true)
const projects = ref<Project[]>([])
const projectFilter = ref<string>('')

async function loadSessions() {
  listLoading.value = true
  try {
    // ★ projectId 是【必填】—— 后端 DiagnosisController#sessions 签的是
    //   `@RequestParam String projectId`，漏传直接 code 40000「缺少必填参数」。
    //   而这一页要展示「所有项目」的会话（左栏可再按项目筛），
    //   所以按项目逐个拉再合并。项目数量少，N 次并发可接受；
    //   将来项目多了应该让后端提供一个聚合接口。
    const ids = projects.value.map((p) => p.id)
    if (!ids.length) {
      sessions.value = []
      return
    }
    const pages = await Promise.all(
      ids.map((id) => getDiagnosisSessions({ projectId: id, size: 30 }).catch(() => null)),
    )
    sessions.value = pages.flatMap((p) => p?.list ?? [])
  } finally {
    listLoading.value = false
  }
}

async function loadProjects() {
  try {
    const page = await getProjects({ size: 100 })
    projects.value = page.list
  } catch {
    projects.value = []
  }
}

const filteredSessions = computed(() =>
  projectFilter.value
    ? sessions.value.filter((s) => s.projectId === projectFilter.value)
    : sessions.value,
)

/* -------- 会话元信息（触发日志等，独立于事件流） -------- */
const sessionMeta = ref<DiagnosisSession | null>(null)
const metaLoading = ref(true)

/* -------- 三阶段状态（从事件流推导） -------- */
const stage3StartAt = ref<number | null>(null)
const stageElapsed = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function trackStage3(events: WsMessage[]) {
  const running = events.find(
    (m) => m.event.type === 'stage' && m.event.stage === 3 && m.event.status === 'running',
  )
  const done = events.find(
    (m) => m.event.type === 'stage' && m.event.stage === 3 && m.event.status === 'done',
  )
  const result = events.find((m) => m.event.type === 'result')
  if (running && !done && !result) {
    if (!stage3StartAt.value) stage3StartAt.value = running.ts
    if (!elapsedTimer) {
      elapsedTimer = setInterval(() => {
        if (stage3StartAt.value) stageElapsed.value = Date.now() - stage3StartAt.value
      }, 200)
    }
  } else {
    if (elapsedTimer) clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

const stages = computed<StageState[]>(() => {
  const ev = store.events
  const has = (stage: 1 | 2 | 3, status: 'running' | 'done') =>
    ev.find((m) => m.event.type === 'stage' && m.event.stage === stage && m.event.status === status)
  const costOf = (stage: 1 | 2 | 3) =>
    (has(stage, 'done') as { event: { costMs?: number } })?.event.costMs
  const ruleHit = ev.some((m) => m.event.type === 'rule_hit')

  const s3Done = has(3, 'done') || ev.some((m) => m.event.type === 'result')
  return [
    {
      id: 1,
      name: '日志预处理 · Drain3',
      status: has(1, 'done') ? 'done' : has(1, 'running') ? 'running' : 'pending',
      costMs: costOf(1),
    },
    {
      id: 2,
      name: '规则引擎预筛',
      status: has(2, 'done') ? 'done' : has(2, 'running') ? 'running' : 'pending',
      costMs: costOf(2),
    },
    {
      id: 3,
      name: 'LangGraph Agent',
      status: ruleHit
        ? 'skipped'
        : s3Done
          ? 'done'
          : has(3, 'running')
            ? 'running'
            : 'pending',
      costMs: s3Done && !ruleHit ? undefined : undefined,
      elapsed: stageElapsed.value,
    },
  ]
})

watch(
  () => store.events.length,
  () => trackStage3(store.events),
  { immediate: true },
)

/* -------- 连接状态圆点 -------- */
const wsDot = computed(() => ({
  open: 'var(--tm-status-success)',
  connecting: 'var(--tm-status-running)',
  closed: 'var(--tm-status-failed)',
  error: 'var(--tm-status-failed)',
  idle: 'var(--tm-text-3)',
}[store.wsState]))

/* -------- 生命周期 -------- */
const sessionId = computed(() => String(route.params.sessionId ?? ''))

async function connect(id: string) {
  metaLoading.value = true
  store.connect(id)
  try {
    sessionMeta.value = await getDiagnosisSession(id)
  } catch {
    sessionMeta.value = null
  } finally {
    metaLoading.value = false
  }
}

onMounted(async () => {
  // ★ 必须先拿项目列表：loadSessions 要靠 projects 里的 id 逐个请求
  //   （业务接口强制带 projectId），并发跑会拿不到 id → 列表恒为空。
  await loadProjects()
  await loadSessions()
  if (sessionId.value) connect(sessionId.value)
})

watch(sessionId, (id) => id && connect(id))

onBeforeUnmount(() => {
  store.close()
  if (elapsedTimer) clearInterval(elapsedTimer)
})

/* -------- 操作 -------- */
const exporting = ref(false)
async function exportSession() {
  const lines = store.events.map(
    (m) => `[seq=${m.seq}] ${JSON.stringify(m.event)}`,
  )
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `diagnosis-${sessionId.value}.jsonl`
  a.click()
  exporting.value = false
  ElMessage.success('已导出事件流')
}

/**
 * 去报告中心，并把当前会话带过去。
 * 报告中心会识别这两个 query 直接打开「生成报告」弹窗并预选这条会话 ——
 * 否则点完只是落到一个空报告列表上，用户会以为"生成报告"坏了。
 */
function goReport() {
  router.push({
    name: 'reports',
    query: {
      ...(sessionId.value ? { sessionId: sessionId.value } : {}),
      ...(sessionMeta.value?.projectId ? { projectId: sessionMeta.value.projectId } : {}),
    },
  })
}

const toolCallCount = computed(
  () => store.events.filter((m) => m.event.type === 'tool_call').length,
)
</script>

<template>
  <!-- 会话不存在 -->
  <EmptyState
    v-if="store.notFound"
    text="会话不存在或已过期"
    action-text="返回工作台"
    @action="router.push('/workbench')"
  />

  <!-- 未选择会话：会话选择页（/diagnosis 入口） -->
  <div v-else-if="!sessionId" class="mx-auto max-w-[880px] space-y-4 py-6">
    <div>
      <h2 class="text-[17px] font-semibold text-text-1">Agent 诊断</h2>
      <p class="mt-1 text-[13px] text-text-3">
        选择一个诊断会话进入实时诊断面板；也可在「日志检索」中对某条 ERROR 日志手动触发诊断。
      </p>
      <p class="mt-1.5 text-[11.5px] text-text-3">
        排序规则：<span class="text-text-2">按所属项目分组</span>（左侧色条标识项目），组内进行中优先、其余按开始时间倒序。
      </p>
    </div>
    <DataState :loading="listLoading" :retry="loadSessions">
      <div v-if="sessions.length" class="rounded-lg border border-border bg-surface p-4">
        <div class="mb-2.5 flex items-center justify-between gap-2">
          <div class="text-[12px] font-semibold uppercase tracking-wide text-text-3">诊断会话</div>
          <el-select
            v-model="projectFilter"
            placeholder="全部项目"
            size="small"
            clearable
            class="!w-[200px]"
          >
            <el-option
              v-for="p in projects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            >
              <span class="inline-flex items-center gap-1.5">
                <span class="h-2 w-2 rounded-[2px]" :style="{ background: projectColor(p.id) }" />
                <span>{{ p.name }}</span>
              </span>
            </el-option>
          </el-select>
        </div>
        <SessionList :sessions="filteredSessions" current-id="" @select="(id) => router.push(`/diagnosis/${id}`)" />
        <div v-if="!filteredSessions.length" class="py-4 text-center text-[12px] text-text-3">
          该项目暂无诊断会话
        </div>
      </div>
      <EmptyState
        v-else
        text="暂无诊断会话"
        action-text="去日志检索触发诊断"
        @action="router.push('/workbench')"
      />
    </DataState>
  </div>

  <div v-else class="flex gap-4">
    <!-- 左栏：会话列表 -->
    <aside class="hidden w-[260px] shrink-0 flex-col gap-2 lg:flex">
      <el-select
        v-model="projectFilter"
        placeholder="全部项目"
        size="small"
        clearable
        filterable
        class="shrink-0"
      >
        <el-option
          v-for="p in projects"
          :key="p.id"
          :label="p.name"
          :value="p.id"
        >
          <span class="inline-flex items-center gap-1.5">
            <span class="h-2 w-2 rounded-[2px]" :style="{ background: projectColor(p.id) }" />
            <span>{{ p.name }}</span>
          </span>
        </el-option>
      </el-select>
      <DataState :loading="listLoading" :retry="loadSessions">
        <SessionList
          :sessions="filteredSessions"
          :current-id="sessionId"
          @select="(id) => router.push(`/diagnosis/${id}`)"
        />
        <div v-if="!filteredSessions.length" class="py-4 text-center text-[12px] text-text-3">
          该项目暂无诊断会话
        </div>
      </DataState>
    </aside>

    <!-- 中栏：事件流 -->
    <section class="min-w-0 flex-1 space-y-3">
      <div class="flex items-center gap-2">
        <span
          v-if="sessionMeta?.projectName"
          class="inline-flex items-center gap-1.5 rounded px-1.5 py-0.5 text-[11.5px] font-medium"
          style="color: var(--tm-text-1); background: var(--tm-code-bg); border: 1px solid var(--tm-border)"
          :title="`所属项目：${sessionMeta.projectName}（${sessionMeta.projectId}）`"
        >
          <span
            class="h-2 w-2 rounded-[2px]"
            :style="{ background: projectColor(sessionMeta.projectId) }"
          />
          {{ sessionMeta.projectName }}
        </span>
        <span class="mono text-[12px] text-text-3">{{ sessionId }}</span>
        <span class="mono flex items-center gap-1.5 text-[11px] text-text-3">
          <span class="h-1.5 w-1.5 rounded-full" :style="{ background: wsDot }" />
          {{ { open: '实时连接', connecting: '连接中…', closed: '已结束', error: '连接错误', idle: '待连接' }[store.wsState] }}
        </span>
        <!-- 重连提示条（断线时） -->
        <span
          v-if="store.wsState === 'connecting' && store.retryCount > 0"
          class="rounded border px-2 py-0.5 text-[11px]"
          style="color: var(--tm-status-running); border-color: var(--tm-status-running)"
        >
          实时连接中断，正在重连（第 {{ store.retryCount }} 次）…
        </span>
        <span
          v-else-if="store.recoveredCount > 0"
          class="rounded border px-2 py-0.5 text-[11px]"
          style="color: var(--tm-status-success); border-color: var(--tm-status-success)"
        >
          已补齐 {{ store.recoveredCount }} 条历史事件
        </span>
        <div class="ml-auto flex items-center gap-2">
          <el-button size="small" @click="exportSession">导出</el-button>
          <el-button size="small" @click="goReport">生成报告</el-button>
        </div>
      </div>

      <DataState :loading="metaLoading" :retry="() => sessionId && connect(sessionId)">
        <template v-if="sessionMeta">
          <ErrorLogBanner :log="sessionMeta.triggerLog" />
        </template>
      </DataState>

      <StagePipeline :stages="stages" />

      <EventStream :events="store.events" />

      <!-- 人机追问：结论产出后可继续与 AI 对话 -->
      <ChatPanel
        :session-id="sessionId"
        :session="sessionMeta"
        :ready="!!store.result || store.isTerminal"
      />
    </section>

    <!-- 右栏：结论 + 元信息 -->
    <aside class="hidden w-[360px] shrink-0 space-y-3 xl:block">
      <DataState :loading="metaLoading">
        <div v-if="store.result" class="space-y-3">
          <ConclusionCard
            :result="store.result"
            :llm-used="sessionMeta?.llmUsed ?? true"
            :matched-analyzer="sessionMeta?.matchedAnalyzer ?? null"
            :total-cost-ms="sessionMeta?.totalCostMs ?? null"
          />
        </div>
        <EmptyState
          v-else-if="store.isTerminal"
          text="本次会话未产出结论"
        />
        <div
          v-else
          class="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border py-10"
        >
          <span class="inline-block h-2 w-2 rounded-full" style="background: var(--tm-status-running); animation: wait-pulse 1.6s ease-in-out infinite" />
          <p class="text-[12.5px] text-text-3">Agent 推理中，结论稍后在这里呈现…</p>
        </div>
      </DataState>

      <div class="rounded-lg border border-border bg-surface p-4 text-[12.5px]">
        <div class="mb-2.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">诊断元信息</div>
        <dl class="space-y-2">
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">状态</dt>
            <dd class="mono">{{ store.status ?? '--' }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">开始时间</dt>
            <dd class="mono" :title="sessionMeta?.startedAt">{{ sessionMeta ? rel(sessionMeta.startedAt) : '--' }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">总耗时</dt>
            <dd class="mono">{{ cost(sessionMeta?.totalCostMs) }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">工具调用次数</dt>
            <dd class="mono">{{ toolCallCount }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">是否调用 LLM</dt>
            <dd class="mono" :style="{ color: sessionMeta?.llmUsed ? 'var(--tm-stage-3)' : 'var(--tm-stage-2)' }">
              {{ sessionMeta?.llmUsed ? '是 · LangGraph Agent' : '否 · 规则命中' }}
            </dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="text-text-3">命中规则</dt>
            <dd class="mono">{{ sessionMeta?.matchedAnalyzer ?? '--' }}</dd>
          </div>
        </dl>
      </div>
    </aside>
  </div>
</template>

<style scoped>
@keyframes wait-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
