<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CirclePlus, Document, Download, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getReports, generateReport, downloadReportMarkdown } from '@/api/report'
import { getProjects } from '@/api/project'
import { getDiagnosisSessions } from '@/api/diagnosis'
import type { Report } from '@/types/report'
import type { Project } from '@/types/project'
import type { DiagnosisSession } from '@/types/diagnosis'
import { useLogFormat } from '@/composables/useLogFormat'

const route = useRoute()
const router = useRouter()
const { rel } = useLogFormat()

const loading = ref(true)
const error = ref<string | null>(null)
const reports = ref<Report[]>([])
const projects = ref<Project[]>([])
const projectFilter = ref('')

async function load() {
  loading.value = true
  error.value = null
  try {
    const [r, p] = await Promise.all([
      getReports({ projectId: projectFilter.value || undefined, size: 50 }),
      getProjects({ size: 100 }),
    ])
    reports.value = r.list
    projects.value = p.list
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await load()
  // 诊断面板的「生成报告」会带 query 跳过来 —— 直接开弹窗并预选那条会话，
  // 这样就不会出现"点了生成报告却落在一个空列表上"的死循环。
  const pid = (route.query.projectId as string | undefined) ?? ''
  const sid = route.query.sessionId as string | undefined
  if (pid || sid) await openGenerate(pid, sid)
})

function projectName(id: string) {
  return projects.value.find((p) => p.id === id)?.name ?? id
}

/* ---------------- 生成报告 ---------------- */

const genDialog = ref(false)
const genProjectId = ref('')
const genSelected = ref<string[]>([])
const genSessions = ref<DiagnosisSession[]>([])
const genLoading = ref(false)
const genSubmitting = ref(false)

/** 只有「已完成」的会话能进报告 —— FAILED 没有结论可聚合 */
const genCandidates = computed(() => genSessions.value.filter((s) => s.status === 'SUCCEEDED'))

/** 列表接口的会话不带 triggerLog，这里兜底，别让一个空字段把整页搞崩 */
function sessionLabel(s: DiagnosisSession) {
  return s.triggerLog?.rawText || s.sessionId
}

async function loadGenSessions() {
  genSelected.value = []
  genSessions.value = []
  if (!genProjectId.value) return
  genLoading.value = true
  try {
    // getDiagnosisSessions 的 projectId 是必填（后端强制按项目过滤）
    const page = await getDiagnosisSessions({ projectId: genProjectId.value, size: 50 })
    genSessions.value = page.list
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '诊断会话加载失败')
  } finally {
    genLoading.value = false
  }
}

async function openGenerate(projectId?: string, sessionId?: string) {
  genProjectId.value = projectId || projectFilter.value || projects.value[0]?.id || ''
  genDialog.value = true
  await loadGenSessions()
  if (sessionId && genCandidates.value.some((s) => s.sessionId === sessionId)) {
    genSelected.value = [sessionId]
  }
}

async function submitGenerate() {
  if (!genProjectId.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  if (!genSelected.value.length) {
    ElMessage.warning('至少选择一个诊断会话')
    return
  }
  genSubmitting.value = true
  try {
    const report = await generateReport(genProjectId.value, genSelected.value)
    ElMessage.success(`报告已生成：${report.title}`)
    genDialog.value = false
    await load()
    router.push(`/reports/${report.id}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    genSubmitting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="报告中心" subtitle="聚合日志与诊断结论，生成可下载的 Markdown 复盘报告">
      <template #actions>
        <el-select v-model="projectFilter" placeholder="按项目筛选" clearable class="!w-48" @change="load">
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
        <el-button type="primary" :icon="CirclePlus" @click="openGenerate()">生成报告</el-button>
      </template>
    </PageHeader>

    <DataState :loading="loading" :error="error" :retry="load">
      <EmptyState
        v-if="!reports.length"
        text="还没有报告 —— 点右上角「生成报告」，勾选已完成的诊断会话即可"
        action-text="去触发一次诊断"
        @action="router.push('/projects')"
      />
      <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="r in reports"
          :key="r.id"
          class="group flex flex-col rounded-lg border border-border bg-surface p-4 transition-all duration-200 hover:border-accent"
          style="box-shadow: var(--tm-shadow)"
        >
          <div class="flex items-start gap-2.5">
            <span class="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-sm bg-accent-soft text-accent">
              <el-icon :size="16"><Document /></el-icon>
            </span>
            <div class="min-w-0">
              <h3 class="truncate text-[14px] font-semibold text-text-1">{{ r.title }}</h3>
              <p class="mt-0.5 text-[12px] text-text-3">{{ projectName(r.projectId) }} · {{ rel(r.createdAt) }}</p>
            </div>
          </div>
          <div class="mono mt-3 flex gap-4 text-[12px] text-text-3">
            <span>日志 {{ r.logCount }} 条</span>
            <span>诊断 {{ r.diagnosisCount }} 次</span>
            <span>会话 {{ r.sessionIds.length }} 个</span>
          </div>
          <div class="mt-3 flex gap-2 border-t border-border pt-3">
            <el-button size="small" :icon="View" @click="router.push(`/reports/${r.id}`)">查看</el-button>
            <el-button size="small" :icon="Download" @click="downloadReportMarkdown(r)">下载 .md</el-button>
          </div>
        </div>
      </div>
    </DataState>

    <!--
      生成报告。
      ★ 后端契约（ReportGenerateDTO）：projectId 必填 + sessionIds 【至少一个】。
      所以「一份报告 = 一个项目 + 你勾选的 N 条诊断会话」，
      不是每条日志一份、也不是每个日志文件一份。
    -->
    <el-dialog v-model="genDialog" title="生成复盘报告" width="640px">
      <div class="mb-4">
        <div class="mb-1.5 text-[12.5px] text-text-3">项目</div>
        <el-select
          v-model="genProjectId"
          placeholder="选择项目"
          class="!w-full"
          @change="loadGenSessions"
        >
          <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="p.name" />
        </el-select>
      </div>

      <div class="mb-1.5 flex items-center justify-between">
        <span class="text-[12.5px] text-text-3">
          选择要纳入报告的诊断会话（只有「已完成」的能选）
        </span>
        <el-button
          link
          size="small"
          :disabled="!genCandidates.length"
          @click="genSelected = genCandidates.map((s) => s.sessionId)"
        >
          全选（{{ genCandidates.length }}）
        </el-button>
      </div>

      <div class="max-h-[320px] overflow-y-auto rounded-sm border border-border">
        <el-checkbox-group v-model="genSelected" class="flex flex-col">
          <el-checkbox
            v-for="s in genCandidates"
            :key="s.sessionId"
            :value="s.sessionId"
            class="!h-auto w-full !items-start border-b border-border px-3 py-2.5 last:border-b-0"
          >
            <div class="min-w-0">
              <div class="truncate text-[12.5px] text-text-2">{{ sessionLabel(s) }}</div>
              <div class="mono mt-0.5 flex flex-wrap gap-3 text-[11px] text-text-3">
                <span>{{ s.sessionId }}</span>
                <span>{{ s.llmUsed ? 'Agent' : '规则命中' }}</span>
                <span>{{ s.totalCostMs == null ? '—' : `${s.totalCostMs}ms` }}</span>
                <span>{{ rel(s.startedAt) }}</span>
              </div>
            </div>
          </el-checkbox>
        </el-checkbox-group>
        <div
          v-if="!genLoading && !genCandidates.length"
          class="px-3 py-8 text-center text-[12px] text-text-3"
        >
          {{ genLoading ? '加载中…' : '该项目还没有「已完成」的诊断会话 —— 先去日志检索触发一次诊断' }}
        </div>
      </div>

      <template #footer>
        <el-button @click="genDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="genSubmitting"
          :disabled="!genSelected.length"
          @click="submitGenerate"
        >
          生成报告{{ genSelected.length ? `（${genSelected.length} 个会话）` : '' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
