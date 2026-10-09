<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadRawFile, UploadUserFile } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Plus, MagicStick, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import LevelTag from '@/components/common/LevelTag.vue'
import CodeBlock from '@/components/common/CodeBlock.vue'
import { getProject } from '@/api/project'
import { getLogs, getLogStats, importLogs, getImportProgress, getNewErrors, ackNewError } from '@/api/log'
import { triggerDiagnosis } from '@/api/diagnosis'
import type { Project } from '@/types/project'
import type { LogEntry, LogStats, LogLevel, NewError } from '@/types/log'
import { useLogFormat } from '@/composables/useLogFormat'
import { useChartTheme } from '@/composables/useChartTheme'

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.id ?? ''))
const { rel, abs, highlight } = useLogFormat()
const { palette } = useChartTheme()

/* -------- 筛选 -------- */
const filters = reactive({
  keyword: '',
  level: [] as LogLevel[],
  clusterId: '',
  page: 1,
  size: 20,
})

/* -------- 数据 -------- */
const project = ref<Project | null>(null)
const logs = ref<LogEntry[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const stats = ref<LogStats | null>(null)
const statsLoading = ref(true)
const selected = ref<LogEntry[]>([])
const newErrors = ref<NewError[]>([])

async function loadLogs() {
  loading.value = true
  error.value = null
  try {
    const res = await getLogs(projectId.value, {
      keyword: filters.keyword || undefined,
      level: filters.level.length ? filters.level : undefined,
      clusterId: filters.clusterId || undefined,
      page: filters.page,
      size: filters.size,
    })
    logs.value = res.list
    total.value = res.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await getLogStats(projectId.value)
  } finally {
    statsLoading.value = false
  }
}

/* -------- 新错误雷达 -------- */
async function loadNewErrors() {
  try {
    newErrors.value = await getNewErrors(projectId.value)
  } catch {
    // 雷达面板是派生展示，失败静默，不打扰主流程
  }
}
async function ack(e: NewError) {
  try {
    await ackNewError(projectId.value, e.clusterId)
    newErrors.value = newErrors.value.filter((x) => x.clusterId !== e.clusterId)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '操作失败')
  }
}

onMounted(async () => {
  project.value = await getProject(projectId.value)
  // URL 中的项目可能已被删除（mock 数据重置后），兜底返回项目管理页
  if (!project.value) {
    ElMessage.warning('项目不存在或已被删除')
    void router.replace('/projects')
    return
  }
  await Promise.all([loadLogs(), loadStats(), loadNewErrors()])
})

function applyFilter() {
  filters.page = 1
  void loadLogs()
}

/* -------- 详情抽屉 -------- */
const detail = ref<LogEntry | null>(null)
const drawerVisible = ref(false)
function openDetail(row: LogEntry) {
  detail.value = row
  drawerVisible.value = true
}

const triggering = ref(false)
async function diagnose(log: LogEntry) {
  triggering.value = true
  try {
    const { sessionId } = await triggerDiagnosis(projectId.value, log.id)
    ElMessage.success('诊断已触发')
    void router.push(`/diagnosis/${sessionId}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '触发失败')
  } finally {
    triggering.value = false
  }
}

/* -------- 批量诊断 -------- */
const batchTotal = ref(0)
const batchDone = ref(0)
let batchTimer: ReturnType<typeof setInterval> | null = null

async function batchDiagnose() {
  if (!selected.value.length) {
    ElMessage.info('先勾选要诊断的日志')
    return
  }
  batchTotal.value = selected.value.length
  batchDone.value = 0
  if (batchTimer) clearInterval(batchTimer)
  batchTimer = setInterval(() => (batchDone.value = Math.min(batchTotal.value, batchDone.value + 1)), 400)
  // 依次触发
  for (const log of selected.value) {
    await triggerDiagnosis(projectId.value, log.id)
  }
  setTimeout(() => {
    if (batchTimer) clearInterval(batchTimer)
    ElMessage.success(`已完成 ${batchTotal.value} 条诊断，去历史会话查看`)
  }, 1200)
}
onBeforeUnmount(() => batchTimer && clearInterval(batchTimer))

/* -------- 导入历史日志 -------- */
const importDialog = ref(false)
const importTaskId = ref<string | null>(null)
/** total 先给 0：真实总数由后端预估后通过 progress 接口回，页面不能自己编一个假分母 */
const importProgress = ref({ total: 0, processed: 0, status: 'RUNNING' as 'RUNNING' | 'DONE' | 'FAILED' })
let importTimer: ReturnType<typeof setInterval> | null = null
/** el-upload 选中的文件。auto-upload=false 时必须自己接住，否则文件根本传不出去 */
const importFileList = ref<UploadUserFile[]>([])

const importPercent = computed(() => {
  const { total, processed } = importProgress.value
  if (!total) return 0
  return Math.min(100, Math.round((processed / total) * 100))
})

async function handleImport() {
  // UploadUserFile.raw 是 UploadRawFile | undefined（= File & { uid }）
  // 类型谓词只能写 UploadRawFile 不能写 File：File 是 UploadRawFile 的父类型，
  // 用父类型做谓词 TS 会报 TS2677（谓词必须收窄而不是放宽）
  const files: UploadRawFile[] = importFileList.value
    .map((f) => f.raw)
    .filter((f): f is UploadRawFile => !!f)
  if (!files.length) {
    ElMessage.warning('请先选择要导入的日志文件')
    return
  }
  importTaskId.value = null
  importProgress.value = { total: 0, processed: 0, status: 'RUNNING' }
  try {
    const { taskId } = await importLogs(projectId.value, files)
    importTaskId.value = taskId
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败')
    return
  }
  if (importTimer) clearInterval(importTimer)
  importTimer = setInterval(async () => {
    const p = await getImportProgress(importTaskId.value!)
    importProgress.value = p
    if (p.status !== 'RUNNING') {
      if (importTimer) clearInterval(importTimer)
      ElMessage.success(`导入完成，共处理 ${p.total} 行`)
      importFileList.value = []
      void loadLogs()
      void loadStats()
      void loadNewErrors()
    }
  }, 600)
}
onBeforeUnmount(() => importTimer && clearInterval(importTimer))

/* -------- 图表 --------
 * 色值必须先经由 useChartTheme 解析成真实颜色：ECharts 画在 canvas 上，
 * canvas 不解析 CSS 变量，直接写 'var(--tm-xxx)' 会静默回落成黑色。
 */
const trendPoints = computed(() => stats.value?.frequencyTrend.length ?? 0)

const levelPie = computed(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, textStyle: { color: palette.value.text3, fontSize: 11 } },
  series: [{
    type: 'pie', radius: ['46%', '68%'], center: ['50%', '42%'], label: { show: false },
    data: (stats.value?.levelDistribution ?? []).map((d) => ({
      name: d.level, value: d.count, itemStyle: { color: palette.value.level[d.level] },
    })),
  }],
}))

const trendLine = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 30, right: 8, top: 16, bottom: 22 },
  xAxis: { type: 'category', data: stats.value?.frequencyTrend.map((d) => d.time) ?? [], axisLabel: { color: palette.value.text3, fontSize: 10 } },
  yAxis: { type: 'value', axisLabel: { color: palette.value.text3, fontSize: 10 }, splitLine: { lineStyle: { color: palette.value.border } } },
  series: [{ type: 'line', smooth: true, symbol: 'none', data: stats.value?.frequencyTrend.map((d) => d.count) ?? [], lineStyle: { color: palette.value.accent, width: 2 }, areaStyle: { color: palette.value.accentSoft } }],
}))

const topBars = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'none' } },
  grid: { left: 8, right: 30, top: 8, bottom: 8, containLabel: true },
  xAxis: { type: 'value', show: false },
  yAxis: { type: 'category', inverse: true, data: (stats.value?.topTemplates ?? []).slice(0, 6).map((t) => t.clusterId), axisLabel: { color: palette.value.text3, fontSize: 10, fontFamily: 'var(--tm-font-mono)' } },
  series: [{ type: 'bar', barWidth: 10, data: (stats.value?.topTemplates ?? []).slice(0, 6).map((t) => ({ value: t.count, itemStyle: { color: palette.value.level[t.level], borderRadius: 3 } })) }],
}))
</script>

<template>
  <div>
    <PageHeader
      v-if="project"
      :title="`日志检索 · ${project.name}`"
      subtitle="ES 全文检索 + Drain3 模板聚合"
    >
      <template #actions>
        <el-button :icon="Plus" @click="importDialog = true">导入历史日志</el-button>
        <el-button type="primary" :icon="MagicStick" :disabled="!selected.length" @click="batchDiagnose">
          批量诊断{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
      </template>
    </PageHeader>

    <!-- 批量进度条 -->
    <div v-if="batchTotal > 0" class="mb-3">
      <el-progress :percentage="Math.round((batchDone / batchTotal) * 100)" :stroke-width="6" />
    </div>

    <!-- 筛选栏 -->
    <div class="sticky top-0 z-10 mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-2.5">
      <el-input
        v-model="filters.keyword"
        placeholder="关键词（等宽匹配原文）"
        class="!w-64"
        clearable
        :prefix-icon="Search"
        @change="applyFilter"
      />
      <el-select v-model="filters.level" multiple collapse-tags placeholder="级别" class="!w-36" @change="applyFilter">
        <el-option v-for="l in (['ERROR', 'WARN', 'INFO', 'DEBUG'] as LogLevel[])" :key="l" :value="l" :label="l" />
      </el-select>
      <el-input v-model="filters.clusterId" placeholder="cluster_id" class="mono !w-40" clearable @change="applyFilter" />
      <el-select v-model="filters.size" class="!w-24" @change="applyFilter">
        <el-option :value="20" label="20 条/页" />
        <el-option :value="50" label="50 条/页" />
        <el-option :value="100" label="100 条/页" />
      </el-select>
      <el-button @click="filters.keyword = ''; filters.level = []; filters.clusterId = ''; applyFilter()">重置</el-button>
    </div>

    <!-- 新错误雷达：历史上从没出现过的新错误模板 -->
    <div v-if="newErrors.length" class="mb-3 rounded-lg border border-border bg-surface p-3">
      <div class="mb-2 flex items-baseline gap-2">
        <span class="text-[13px] font-medium">新错误雷达</span>
        <span class="text-[11.5px] text-text-3">发现了 {{ newErrors.length }} 个从没见过的错误模板</span>
      </div>
      <div class="space-y-1.5">
        <div
          v-for="e in newErrors"
          :key="e.clusterId"
          class="flex items-center gap-2.5 rounded border border-border px-2.5 py-1.5"
        >
          <span
            class="mono cursor-pointer text-[11px] text-accent"
            @click="filters.clusterId = e.clusterId; applyFilter()"
          >
            {{ e.clusterId }}
          </span>
          <span class="min-w-0 flex-1 truncate text-[12px] text-text-2">{{ e.template || '（无模板）' }}</span>
          <span class="shrink-0 text-[11px] text-text-3">{{ rel(e.firstSeenAt) }} · {{ e.count }} 条</span>
          <el-button link size="small" @click="ack(e)">知道了</el-button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-12 gap-4">
      <!-- 左 8 列：日志表 -->
      <div class="col-span-12 lg:col-span-8">
        <DataState :loading="loading" :error="error" :retry="loadLogs">
          <EmptyState v-if="!logs.length" text="没有匹配的日志，试试放宽筛选条件" />
          <template v-else>
            <el-table
              :data="logs"
              class="log-table"
              @selection-change="(rows: LogEntry[]) => selected = rows"
              @row-click="openDetail"
            >
              <el-table-column type="selection" width="42" />
              <el-table-column label="时间" width="160">
                <template #default="{ row }">
                  <el-tooltip :content="abs(row.timestamp)">
                    <span class="mono text-[11.5px] text-text-3">{{ rel(row.timestamp) }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="级别" width="84">
                <template #default="{ row }">
                  <LevelTag :level="row.level" />
                </template>
              </el-table-column>
              <el-table-column label="模板" width="100">
                <template #default="{ row }">
                  <span class="mono cursor-pointer text-[11px] text-accent" @click.stop="filters.clusterId = row.clusterId; applyFilter()">
                    {{ row.clusterId }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="内容" min-width="320">
                <template #default="{ row }">
                  <span
                    class="mono block truncate text-[12px] text-text-1"
                    v-html="highlight(row.rawText, filters.keyword)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button link size="small" type="primary" @click.stop="diagnose(row as LogEntry)">诊断</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="mt-3 flex justify-end">
              <el-pagination
                v-model:current-page="filters.page"
                :total="total"
                :page-size="filters.size"
                layout="total, prev, pager, next"
                @current-change="loadLogs"
              />
            </div>
          </template>
        </DataState>
      </div>

      <!-- 右 4 列：统计 -->
      <div class="col-span-12 space-y-4 lg:col-span-4">
        <DataState :loading="statsLoading" :retry="loadStats">
          <div class="rounded-lg border border-border bg-surface p-4">
            <div class="mb-1 text-[13px] font-medium">级别分布</div>
            <VChart :option="levelPie" autoresize style="height: 190px" />
          </div>
          <div class="mt-4 rounded-lg border border-border bg-surface p-4">
            <div class="mb-1 text-[13px] font-medium">Top 模板聚类</div>
            <VChart :option="topBars" autoresize style="height: 180px" />
          </div>
          <div class="mt-4 rounded-lg border border-border bg-surface p-4">
            <div class="mb-1 text-[13px] font-medium">近 24h 频次趋势</div>
            <div class="relative">
              <VChart :option="trendLine" autoresize style="height: 150px" />
              <!-- 0~1 个点画不出折线，说明原因，别留一片空白让人以为图坏了 -->
              <div
                v-if="trendPoints < 2"
                class="pointer-events-none absolute inset-0 flex items-center justify-center"
              >
                <span class="rounded border border-border bg-surface px-2 py-0.5 text-[11.5px] text-text-3">
                  近 24h 只有 {{ trendPoints }} 个时间点有日志
                </span>
              </div>
            </div>
          </div>
        </DataState>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" title="日志详情" size="520px">
      <template v-if="detail">
        <div class="space-y-4">
          <div class="flex flex-wrap items-center gap-2.5">
            <LevelTag :level="detail.level" />
            <span class="mono text-[11.5px] text-text-3">{{ abs(detail.timestamp) }}</span>
            <span class="mono rounded border border-border px-1.5 text-[11px] text-text-3">{{ detail.clusterId }}</span>
            <span class="mono text-[11px] text-text-3">{{ detail.filePath }}:{{ detail.lineNumber }}</span>
          </div>
          <div>
            <div class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">原文</div>
            <CodeBlock :code="detail.rawText" language="accesslog" :max-height="120" />
          </div>
          <div>
            <div class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">Drain3 模板</div>
            <CodeBlock :code="detail.template" language="accesslog" :max-height="80" />
          </div>
          <div v-if="detail.stackTrace">
            <div class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">异常堆栈</div>
            <CodeBlock :code="detail.stackTrace" language="java" :max-height="260" />
          </div>
          <el-button type="primary" :loading="triggering" @click="diagnose(detail)">对此日志发起诊断</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 导入弹窗 -->
    <el-dialog v-model="importDialog" title="导入历史日志" width="480px">
      <!--
        v-model:file-list 必须绑：auto-upload=false 时 el-upload 只把选中项放在自己的
        内部状态里，不绑出来就永远拿不到 File 对象，点"开始导入"只会发一个空请求。
        multiple 打开是因为后端 /logs/import 接的就是多文件（files 可重复）。
      -->
      <el-upload
        v-model:file-list="importFileList"
        drag
        multiple
        :auto-upload="false"
        accept=".log,.txt"
      >
        <div class="flex flex-col items-center gap-2 py-6 text-text-3">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="var(--tm-accent)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 16V4m0 0 -4 4m4-4 4 4" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
          </svg>
          <p class="text-[13px]">拖拽 .log 文件到这里，或点击选择（可多选）</p>
          <p class="mono text-[11px] text-text-3">流式读取 + bulk 批量写 ES，防大文件 OOM</p>
        </div>
      </el-upload>
      <template v-if="importTaskId">
        <el-progress class="mt-3" :percentage="importPercent" :stroke-width="6" />
        <p class="mono mt-1.5 text-[11.5px] text-text-3">{{ importProgress.processed }} / {{ importProgress.total }} 行</p>
      </template>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!importFileList.length" @click="handleImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>
