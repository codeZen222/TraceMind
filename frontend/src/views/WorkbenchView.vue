<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { getProjects } from '@/api/project'
import { getDiagnosisSessions } from '@/api/diagnosis'
import { getLogStats } from '@/api/log'
import { getSystemHealth } from '@/api/settings'
import type { Project } from '@/types/project'
import type { DiagnosisSession } from '@/types/diagnosis'
import type { LogStats } from '@/types/log'
import type { HealthItem } from '@/types/common'
import DataState from '@/components/common/DataState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useLogFormat } from '@/composables/useLogFormat'
import { useChartTheme } from '@/composables/useChartTheme'

use([CanvasRenderer, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const router = useRouter()
const { rel, cost, logTitle } = useLogFormat()
const { palette } = useChartTheme()

const loading = ref(true)
const error = ref<string | null>(null)
const projects = ref<Project[]>([])
const sessions = ref<DiagnosisSession[]>([])
const stats = ref<LogStats | null>(null)
const health = ref<HealthItem[]>([])

const errorToday = computed(() => projects.value.reduce((a, p) => a + p.errorCount24h, 0))
const diagnosisTotal = computed(() => projects.value.reduce((a, p) => a + p.diagnosisCount, 0))
const ruleCount = computed(() => sessions.value.filter((s) => !s.llmUsed).length)
const interceptRate = computed(() =>
  sessions.value.length ? Math.round((ruleCount.value / sessions.value.length) * 100) : null,
)

/**
 * silent=true 时为后台静默刷新：不置 loading、不清 error，
 * 页面内容保持可点击，避免 30s 轮询期间整页被骨架屏替换导致「点了没反应」。
 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = null
  }
  try {
    // ① 先拿项目列表 —— 四个接口里只有它不需要 projectId
    const p = await getProjects({ size: 100 })
    projects.value = p.list

    // ② 跨项目汇总：sessions 不传 projectId（后端按 owner 查全部），
    //    趋势图循环所有项目加总 frequencyTrend —— 工作台真正变成「全局看板」。
    const [s, st, h] = await Promise.all([
      getDiagnosisSessions({ size: 30 }),
      p.list.length ? mergeTrend(p.list) : Promise.resolve(null),
      getSystemHealth(),
    ])
    sessions.value = s.list
    stats.value = st
    health.value = h
  } catch (e) {
    if (!silent) error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    if (!silent) loading.value = false
  }
}

/**
 * 跨项目加总 24h 趋势：循环调每个项目的 stats，把 frequencyTrend 按小时加总。
 * 工作台只用了 frequencyTrend，levelDistribution / topTemplates 在这里不跨项目合并
 * （跨项目「top 模板」语义复杂，工作台本来也不展示它们）。
 */
async function mergeTrend(list: Project[]): Promise<LogStats> {
  const all = await Promise.all(list.map((pr) => getLogStats(pr.id)))
  const byHour = new Map<string, number>()
  for (const st of all) {
    for (const point of st.frequencyTrend) {
      byHour.set(point.time, (byHour.get(point.time) ?? 0) + point.count)
    }
  }
  const frequencyTrend = Array.from(byHour.entries())
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([time, count]) => ({ time, count }))
  return { levelDistribution: [], frequencyTrend, topTemplates: [] }
}

/* 30s 自动刷新（页面隐藏时暂停） */
let timer: ReturnType<typeof setInterval> | null = null
function onVisibility() {
  if (document.visibilityState === 'visible') {
    if (!timer) timer = setInterval(() => void load(true), 30_000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}
onMounted(() => {
  void load()
  document.addEventListener('visibilitychange', onVisibility)
  timer = setInterval(() => void load(true), 30_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})

/*
 * 图表配色：色值由 useChartTheme 从 CSS 变量解析成真实颜色后再喂给 ECharts。
 * 直接把 'var(--tm-xxx)' 写进 option 是不生效的 —— canvas 不认 CSS 变量，会静默回落成黑色。
 */
const trendPoints = computed(() => stats.value?.frequencyTrend.length ?? 0)

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 36, right: 12, top: 20, bottom: 24 },
  xAxis: { type: 'category', data: stats.value?.frequencyTrend.map((d) => d.time) ?? [], axisLabel: { color: palette.value.text3, fontSize: 10 } },
  yAxis: { type: 'value', axisLabel: { color: palette.value.text3, fontSize: 10 }, splitLine: { lineStyle: { color: palette.value.border } } },
  series: [
    {
      type: 'line',
      smooth: true,
      symbol: 'none',
      data: stats.value?.frequencyTrend.map((d) => d.count) ?? [],
      lineStyle: { color: palette.value.accent, width: 2 },
      areaStyle: { color: palette.value.accentSoft },
    },
  ],
}))

const pathOption = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, textStyle: { color: palette.value.text3, fontSize: 11 } },
  series: [
    {
      type: 'pie',
      radius: ['52%', '74%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: [
        { name: '规则命中 · 零 LLM', value: ruleCount.value, itemStyle: { color: palette.value.stage2 } },
        { name: 'Agent 深度诊断', value: sessions.value.length - ruleCount.value, itemStyle: { color: palette.value.stage3 } },
      ],
    },
  ],
}))

const metricCards = computed(() => [
  { label: '项目总数', value: projects.value.length ? String(projects.value.length) : '--' },
  { label: '今日 ERROR', value: projects.value.length ? String(errorToday.value) : '--' },
  { label: '规则拦截率', value: interceptRate.value === null ? '--' : `${interceptRate.value}%` },
  { label: 'AI 诊断次数', value: projects.value.length ? String(diagnosisTotal.value) : '--' },
])
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-2">
      <h2 class="text-[17px] font-semibold">TraceMind · 根因诊断工作台</h2>
      <div class="ml-auto flex items-center gap-3">
        <span class="flex items-center gap-1.5 text-[12px] text-text-3">
          <span
            class="h-1.5 w-1.5 rounded-full"
            :style="{ background: health.every((h) => h.ok) ? 'var(--tm-status-success)' : 'var(--tm-status-failed)' }"
          />
          数据源正常
        </span>
      </div>
    </div>

    <DataState :loading="loading" :error="error" :retry="load">
      <!-- 空态引导 -->
      <EmptyState
        v-if="!projects.length"
        text="还没有项目，先去创建一个吧"
        action-text="创建第一个项目"
        @action="router.push('/projects')"
      />

      <template v-else>
        <!-- 指标卡（status bar 式） -->
        <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div
            v-for="c in metricCards"
            :key="c.label"
            class="rounded-lg border border-border bg-surface px-4 py-3"
            style="box-shadow: var(--tm-shadow)"
          >
            <div class="text-[12px] text-text-3">{{ c.label }}</div>
            <div class="mono mt-1 text-[22px] font-semibold text-text-1">{{ c.value }}</div>
          </div>
        </div>

        <div class="mt-4 grid grid-cols-12 gap-4">
          <!-- 左 8 列：错误趋势 -->
          <div class="col-span-12 rounded-lg border border-border bg-surface p-4 lg:col-span-8">
            <div class="mb-2 flex items-center gap-2 text-[13.5px] font-medium">
              近 24h 错误趋势
              <span v-if="trendPoints" class="mono text-[11px] font-normal text-text-3">按小时 · {{ trendPoints }} 个时间点</span>
            </div>
            <div class="relative">
              <VChart :option="trendOption" autoresize style="height: 240px" />
              <!-- 只有 0~1 个数据点时折线画不出来，页面会像"坏了"。这里说明白原因，而不是留一片空白 -->
              <div
                v-if="trendPoints < 2"
                class="pointer-events-none absolute inset-0 flex items-center justify-center"
              >
                <span class="rounded border border-border bg-surface px-2.5 py-1 text-[12px] text-text-3">
                  近 24h 只有 {{ trendPoints }} 个时间点有日志，暂时画不出趋势
                </span>
              </div>
            </div>
          </div>
          <!-- 右 4 列：诊断路径分布 -->
          <div class="col-span-12 rounded-lg border border-border bg-surface p-4 lg:col-span-4">
            <div class="mb-2 text-[13.5px] font-medium">诊断路径分布</div>
            <VChart :option="pathOption" autoresize style="height: 240px" />
          </div>

          <!-- 左 7 列：最近诊断 -->
          <div class="col-span-12 rounded-lg border border-border bg-surface p-4 lg:col-span-7">
            <div class="mb-2.5 text-[13.5px] font-medium">最近诊断会话</div>
            <button
              v-for="s in sessions.slice(0, 6)"
              :key="s.sessionId"
              class="flex w-full items-center gap-3 rounded-sm px-2 py-2 text-left transition-colors hover:bg-accent-soft"
              @click="router.push(`/diagnosis/${s.sessionId}`)"
            >
              <span
                class="h-1.5 w-1.5 shrink-0 rounded-full"
                :style="{ background: { RUNNING: 'var(--tm-status-running)', SUCCEEDED: 'var(--tm-status-success)', FAILED: 'var(--tm-status-failed)', DEGRADED: 'var(--tm-level-warn)' }[s.status] }"
              />
              <!-- 标题与「诊断页 · 会话列表」共用同一套规则，同一条会话在两处显示同一个标题 -->
              <span
                class="min-w-0 flex-1 truncate text-[12px] text-text-2"
                :title="s.triggerLog?.rawText"
              >{{ logTitle(s.triggerLog) }}</span>
              <span
                class="shrink-0 rounded px-1.5 text-[11px]"
                :style="s.llmUsed ? { color: 'var(--tm-stage-3)' } : { color: 'var(--tm-stage-2)' }"
              >
                {{ s.llmUsed ? 'Agent' : '零 LLM' }}
              </span>
              <span class="mono w-14 shrink-0 text-right text-[11.5px] text-text-3">{{ cost(s.totalCostMs) }}</span>
              <span class="w-16 shrink-0 text-right text-[11.5px] text-text-3">{{ rel(s.startedAt) }}</span>
            </button>
          </div>

          <!-- 右 5 列：依赖健康 -->
          <div class="col-span-12 rounded-lg border border-border bg-surface p-4 lg:col-span-5">
            <div class="mb-2.5 text-[13.5px] font-medium">依赖健康</div>
            <div class="space-y-2">
              <div
                v-for="h in health"
                :key="h.name"
                class="flex items-center gap-2.5"
                :title="h.ok ? `${h.name} 正常 · ${h.latencyMs}ms` : `${h.name} 连接失败`"
              >
                <span
                  class="h-1.5 w-1.5 rounded-full"
                  :style="{ background: h.ok ? 'var(--tm-status-success)' : 'var(--tm-status-failed)' }"
                />
                <span class="text-[13px] text-text-2">{{ h.name }}</span>
                <!-- 依赖不通时不再显示耗时数字：失败前那点耗时看着像"正常响应"，反而误导 -->
                <span
                  class="mono ml-auto text-[11.5px]"
                  :style="{ color: h.ok ? 'var(--tm-text-3)' : 'var(--tm-status-failed)' }"
                >
                  {{ h.ok ? `${h.latencyMs}ms` : '失败' }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </DataState>
  </div>
</template>
