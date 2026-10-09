<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Monitor, Search, Delete, EditPen, MagicStick, FolderOpened, UploadFilled, Connection } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getProjects, createProject, updateProject, deleteProject } from '@/api/project'
import { importLogs, getImportProgress } from '@/api/log'
import type { Project, ProjectStatus, ProjectForm } from '@/types/project'
import { useLogFormat } from '@/composables/useLogFormat'
import { projectColor } from '@/utils/projectColor'

const route = useRoute()
const router = useRouter()
const { rel, ellipsisPath } = useLogFormat()

const loading = ref(true)
const error = ref<string | null>(null)
const list = ref<Project[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
/** 顶部全局搜索框会跳到这里并带 ?keyword=，所以初值取自 query */
const keyword = ref(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const statusFilter = ref<ProjectStatus | 'ALL'>('ALL')

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await getProjects({ page: page.value, size: size.value, status: statusFilter.value, keyword: keyword.value })
    list.value = res.list
    total.value = res.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 已经在本页时再从顶部搜索框搜一次，query 变了也要重新过滤 */
watch(
  () => route.query.keyword,
  (value) => {
    const next = typeof value === 'string' ? value : ''
    if (next === keyword.value) return
    keyword.value = next
    page.value = 1
    void load()
  },
)

/** 项目名允许重复（demo 里就有三个 order-service），列表里补一个不重复的 id 才能分辨 */
function shortId(id: string) {
  return id.length > 12 ? `${id.slice(0, 11)}…` : id
}

/* -------- 新建 / 编辑弹窗 -------- */
const dialogVisible = ref(false)
const editing = ref<Project | null>(null)
/** 新建时的接入方式：上传日志文件夹（默认） / 监听服务器路径 */
const mode = ref<'UPLOAD' | 'PATH'>('UPLOAD')

const formRef = ref<FormInstance>()
const form = reactive<ProjectForm>({
  name: '', description: '', status: 'DEVELOPING', language: 'Java / Spring Boot',
  logPath: '', healthUrl: '', sourceRoot: '',
})

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  logPath:
    mode.value === 'PATH' && !editing.value
      ? [
          { required: true, message: '请输入日志文件绝对路径', trigger: 'blur' },
          { pattern: /^([A-Za-z]:\\|\/)/, message: '必须是绝对路径（如 /var/log/app.log 或 C:\\logs\\app.log）', trigger: 'blur' },
        ]
      : [],
}))

function openCreate() {
  editing.value = null
  mode.value = 'UPLOAD'
  Object.assign(form, {
    name: '', description: '', status: 'DEVELOPING', language: 'Java / Spring Boot',
    logPath: '', healthUrl: '', sourceRoot: '',
  })
  selectedFileObjs.value = []
  folderName.value = ''
  dialogVisible.value = true
  nextTick(() => prepareFolderInput())
}

function openEdit(p: Project) {
  editing.value = p
  Object.assign(form, {
    name: p.name, description: p.description, status: p.status, language: p.language,
    logPath: p.logPath, healthUrl: p.healthUrl, sourceRoot: p.sourceRoot,
  })
  dialogVisible.value = true
}

async function submit() {
  await formRef.value?.validate()
  if (editing.value) {
    await updateProject(editing.value.id, form)
    ElMessage.success('项目已更新')
    dialogVisible.value = false
    await load()
    return
  }
  if (mode.value === 'UPLOAD') {
    await startParse()
    return
  }
  await createProject({ ...form, source: 'PATH' })
  ElMessage.success('项目已创建')
  dialogVisible.value = false
  await load()
}

/* -------- 上传文件夹（复盘分析） -------- */
const folderInput = ref<HTMLInputElement | null>(null)
/**
 * ★ 必须留住 File 对象本身。
 *   之前这里只存 { name, size }，File 被丢掉 → 上传时无文件可发，
 *   「上传日志文件夹」就退化成了「只建项目、一个字节都不传」。
 */
const selectedFileObjs = ref<File[]>([])
/** 展示用的轻量视图（模板只用到 name / size） */
const selectedFiles = computed(() =>
  selectedFileObjs.value.map((f) => ({
    name: (f as FileWithPath).webkitRelativePath || f.name,
    size: f.size,
  })),
)
const folderName = ref('')
/** webkitdirectory 非标准属性，需手动挂到 DOM（Chrome / Edge 支持） */
function prepareFolderInput() {
  const el = folderInput.value
  if (!el) return
  el.setAttribute('webkitdirectory', '')
  el.setAttribute('directory', '')
}

type FileWithPath = File & { webkitRelativePath?: string }

function onFolderChange(e: Event) {
  const input = e.target as HTMLInputElement
  const all = Array.from(input.files ?? []) as FileWithPath[]
  const LOG_EXT = /\.(log|txt|out|trace|gc)$/i
  const logs = all.filter((f) => LOG_EXT.test(f.name))
  const picked = (logs.length ? logs : all).slice(0, 500)
  selectedFileObjs.value = picked
  const root = picked[0]?.webkitRelativePath?.split('/')[0]
  folderName.value = root ?? ''
  // 名称默认取文件夹名
  if (!form.name && folderName.value) form.name = folderName.value
  if (!selectedFileObjs.value.length) ElMessage.warning('该文件夹里没有找到日志文件（.log / .txt / .out）')
}

function fmtSize(bytes: number) {
  if (bytes > 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  if (bytes > 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return bytes + ' B'
}

/**
 * 上传 + 解析（真实链路）。
 *
 * ★ 之前这里是假的：一个 setInterval 造进度条，跑完只 createProject，
 *   从来没调过 importLogs —— 所以"导入成功"只是提示语，ES 里一条日志都没有。
 *
 * 现在按后端契约走三步：
 *   ① createProject  → 拿到 projectId（导入接口挂在 /api/projects/{id}/logs/import 下）
 *   ② importLogs     → 真上传文件，拿到 taskId（后端异步导入）
 *   ③ getImportProgress 轮询 → 用后端返回的 processed/total 画进度
 */
const parsing = ref(false)
const parseProgress = ref(0)
const parsedCount = ref(0)
const parseTotal = ref(0)
let parseTimer: ReturnType<typeof setInterval> | null = null

async function startParse() {
  if (!selectedFileObjs.value.length) {
    ElMessage.warning('请先选择日志文件夹')
    return
  }
  parsing.value = true
  parseProgress.value = 0
  parsedCount.value = 0
  parseTotal.value = 0
  try {
    // ① 先建项目，拿到 id
    const created = await createProject({
      ...form,
      source: 'UPLOAD',
      folderName: folderName.value || 'uploaded-logs',
      files: selectedFiles.value.map((f) => f.name),
    })
    if (!created?.id) throw new Error('项目已创建但未返回 id，无法继续上传')

    // ② 真上传
    const { taskId } = await importLogs(created.id, selectedFileObjs.value)

    // ③ 轮询真实进度
    await new Promise<void>((resolve, reject) => {
      parseTimer = setInterval(() => {
        void (async () => {
          try {
            const p = await getImportProgress(taskId)
            parseTotal.value = p.total
            parsedCount.value = p.processed
            parseProgress.value = p.total > 0 ? Math.min(100, (p.processed / p.total) * 100) : 0
            if (p.status !== 'RUNNING') {
              if (parseTimer) {
                clearInterval(parseTimer)
                parseTimer = null
              }
              if (p.status === 'FAILED') reject(new Error('后端导入失败，请查看服务端日志'))
              else resolve()
            }
          } catch (err) {
            if (parseTimer) {
              clearInterval(parseTimer)
              parseTimer = null
            }
            reject(err instanceof Error ? err : new Error('查询导入进度失败'))
          }
        })()
      }, 600)
    })

    parseProgress.value = 100
    ElMessage.success(`已导入 ${parsedCount.value} 行日志，项目创建成功`)
    selectedFileObjs.value = []
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    if (parseTimer) {
      clearInterval(parseTimer)
      parseTimer = null
    }
    parsing.value = false
  }
}

onBeforeUnmount(() => {
  if (parseTimer) clearInterval(parseTimer)
})

/* -------- 表格操作 -------- */
async function remove(p: Project) {
  await ElMessageBox.confirm(
    '删除后该项目所有日志与诊断记录将不可恢复，确认删除？',
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await deleteProject(p.id)
  ElMessage.success('已删除')
  await load()
}

function copyPath(p: Project) {
  navigator.clipboard.writeText(p.logPath)
  ElMessage.success({ message: '路径已复制', duration: 1200 })
}

function triggerDiagnosis(p: Project) {
  router.push(`/projects/${p.id}/logs`)
}
</script>

<template>
  <div>
    <PageHeader title="项目管理" subtitle="上传日志文件夹做复盘分析，或接入服务器路径做实时诊断">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">接入项目</el-button>
      </template>
    </PageHeader>

    <!-- 筛选栏 -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="keyword"
        placeholder="搜索项目名"
        class="!w-56"
        clearable
        :prefix-icon="Search"
        @change="page = 1; load()"
      />
      <el-radio-group v-model="statusFilter" @change="page = 1; load()">
        <el-radio-button value="ALL">全部</el-radio-button>
        <el-radio-button value="DEVELOPING">开发中</el-radio-button>
        <el-radio-button value="DEPLOYED">已上线</el-radio-button>
      </el-radio-group>
    </div>

    <DataState :loading="loading" :error="error" :retry="load">
      <EmptyState
        v-if="!list.length"
        text="还没有项目——上传一个日志文件夹，或接入服务器路径"
        action-text="接入第一个项目"
        @action="openCreate"
      />
      <template v-else>
        <el-table :data="list" class="log-table">
          <el-table-column label="项目名" min-width="200">
            <template #default="{ row }">
              <div class="flex items-center gap-1.5">
                <span
                  class="h-2.5 w-2.5 shrink-0 rounded-[2px]"
                  :style="{ background: projectColor((row as Project).id) }"
                />
                <el-icon v-if="row.source === 'UPLOAD'" :size="14" class="shrink-0 text-text-3"><FolderOpened /></el-icon>
                <span class="text-[13px] font-medium text-text-1">{{ row.name }}</span>
              </div>
              <!-- 描述可以为空，空着就整行空白 —— 项目名允许重复（demo 里有三个 order-service），
                   补一个 id 才能分辨哪行是哪个 -->
              <div class="flex items-center gap-2 text-[11.5px] text-text-3">
                <span class="min-w-0 truncate" :title="row.description">{{ row.description || '（无描述）' }}</span>
                <span class="mono ml-auto shrink-0" :title="row.id">{{ shortId(row.id) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <!-- 上传型项目没有"实时监听"这回事，这个状态对它们只是标记位 —— 用 title 说清楚，
                   不改成别的词是因为筛选栏就是按这个字段过滤的，改了标签筛选会看不懂 -->
              <el-tooltip
                :content="row.source === 'UPLOAD'
                  ? '上传型项目：状态仅作标记，不做实时监听'
                  : (row.status === 'DEVELOPING' ? '开发中：可启动实时监听' : '已上线：做复盘分析')"
                placement="top"
              >
                <el-tag size="small" :type="row.status === 'DEVELOPING' ? 'warning' : 'success'" effect="plain" round>
                  {{ row.status === 'DEVELOPING' ? '开发中' : '已上线' }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="language" label="技术栈" width="132" show-overflow-tooltip />
          <el-table-column label="日志来源" min-width="172">
            <template #default="{ row }">
              <!-- ★ 插槽必须只有【一个根元素】：Element Plus 的单元格渲染器
                   与 el-tooltip 的 ElOnlyChild 都期望拿到单个 vnode，
                   返回多个根节点时会去读 undefined 的 .type，
                   dev 下抛错会中断 <RouterView> 的 patch → 点菜单 URL 变了但页面不换。 -->
              <div>
                <template v-if="row.source === 'UPLOAD'">
                  <div class="flex items-center gap-1.5">
                    <el-tag size="small" type="info" effect="plain" round>上传</el-tag>
                    <span class="mono text-[11.5px] text-text-2">{{ row.logPath }}/</span>
                  </div>
                  <div class="mt-0.5 text-[11px] text-text-3">{{ row.fileCount ?? 0 }} 个日志文件</div>
                </template>
                <template v-else>
                  <el-tag size="small" type="info" effect="plain" round>监听</el-tag>
                  <el-tooltip :content="row.logPath || ''" placement="top">
                    <span class="mono ml-1.5 cursor-pointer text-[11.5px] text-text-2 hover:text-accent" @click="copyPath(row as Project)">
                      {{ ellipsisPath(row.logPath) }}
                    </span>
                  </el-tooltip>
                </template>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="24h 错误" width="86" align="right">
            <template #default="{ row }">
              <span class="mono" :style="{ color: row.errorCount24h > 30 ? 'var(--tm-level-error)' : row.errorCount24h > 0 ? 'var(--tm-level-warn)' : 'var(--tm-text-3)' }">
                {{ row.errorCount24h }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="diagnosisCount" label="诊断次数" width="86" align="right" />
          <el-table-column label="更新时间" width="98">
            <template #default="{ row }">
              <span class="text-[12px] text-text-3">{{ rel(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <!--
            操作列原来是 240px，5 个「图标+文字」按钮放不下 → 折成两行，
            并且把左边「诊断次数」的表头也挤掉了。现在把低频的编辑/删除收成图标按钮
            （el-tooltip 补文案），宽度自然回到一行内。
          -->
          <el-table-column label="操作" width="252" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.source !== 'UPLOAD'" link size="small" :icon="Monitor" @click="router.push(`/projects/${row.id}/monitor`)">监听</el-button>
              <el-button link size="small" :icon="Search" @click="router.push(`/projects/${row.id}/logs`)">日志</el-button>
              <el-button link size="small" :icon="MagicStick" @click="triggerDiagnosis(row as Project)">诊断</el-button>
              <el-tooltip content="编辑项目" placement="top">
                <el-button link size="small" :icon="EditPen" @click="openEdit(row as Project)" />
              </el-tooltip>
              <el-tooltip content="删除项目" placement="top">
                <el-button link size="small" type="danger" :icon="Delete" @click="remove(row as Project)" />
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <div class="mt-3 flex justify-end">
          <el-pagination
            v-model:current-page="page"
            :total="total"
            :page-size="size"
            layout="total, prev, pager, next"
            @current-change="load"
          />
        </div>
      </template>
    </DataState>

    <!-- 新建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑项目' : '接入项目'"
      width="620px"
      destroy-on-close
      :close-on-click-modal="!parsing"
      :show-close="!parsing"
    >
      <!-- 编辑模式：常规表单 -->
      <el-form v-if="editing" ref="formRef" :model="form" :rules="rules" label-width="96px" label-position="right">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如 order-service 订单中心" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明项目用途" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio value="DEVELOPING">开发中</el-radio>
            <el-radio value="DEPLOYED">已上线</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="技术栈">
          <el-input v-model="form.language" placeholder="Java / Spring Boot 3.2" />
        </el-form-item>
        <el-form-item label="日志路径">
          <el-input v-model="form.logPath" placeholder="/var/log/order-service/app.log" class="mono" />
        </el-form-item>
        <el-form-item label="健康端点">
          <el-input v-model="form.healthUrl" placeholder="http://127.0.0.1:8081/actuator/health（选填）" class="mono" />
        </el-form-item>
        <el-form-item label="源码根目录">
          <el-input v-model="form.sourceRoot" placeholder="/workspace/order-service/src/main/java（选填）" class="mono" />
        </el-form-item>
      </el-form>

      <!-- 新建模式 -->
      <template v-else>
        <!-- 第一步：选择接入方式 -->
        <div v-if="!selectedFiles.length" class="mb-4 grid grid-cols-2 gap-3">
          <button
            class="rounded-lg border-2 p-4 text-left transition-all"
            :style="mode === 'UPLOAD'
              ? { borderColor: 'var(--tm-accent)', background: 'var(--tm-accent-soft)' }
              : { borderColor: 'var(--tm-border)' }"
            @click="mode = 'UPLOAD'"
          >
            <el-icon :size="22" style="color: var(--tm-accent)"><UploadFilled /></el-icon>
            <div class="mt-2 text-[13.5px] font-medium text-text-1">上传日志文件夹</div>
            <div class="mt-1 text-[12px] leading-relaxed text-text-3">
              本地有一批历史日志想做复盘分析？选这个——解析入库后即可检索与触发诊断。
            </div>
          </button>
          <button
            class="rounded-lg border-2 p-4 text-left transition-all"
            :style="mode === 'PATH'
              ? { borderColor: 'var(--tm-accent)', background: 'var(--tm-accent-soft)' }
              : { borderColor: 'var(--tm-border)' }"
            @click="mode = 'PATH'"
          >
            <el-icon :size="22" style="color: var(--tm-accent)"><Connection /></el-icon>
            <div class="mt-2 text-[13.5px] font-medium text-text-1">监听服务器路径</div>
            <div class="mt-1 text-[12px] leading-relaxed text-text-3">
              应用正在运行、日志在服务器上？填路径接入——ERROR 落盘即触发实时诊断。
            </div>
          </button>
        </div>

        <!-- 上传模式 -->
        <div v-if="mode === 'UPLOAD'">
          <input ref="folderInput" type="file" multiple hidden accept=".log,.txt,.out,.trace,.gc" @change="onFolderChange" />

          <template v-if="!selectedFiles.length">
            <div
              class="flex cursor-pointer flex-col items-center gap-2 rounded-lg border border-dashed py-10 transition-colors"
              style="border-color: var(--tm-border)"
              @click="folderInput?.click()"
              @mouseover="($event.currentTarget as HTMLElement).style.borderColor = 'var(--tm-accent)'"
              @mouseleave="($event.currentTarget as HTMLElement).style.borderColor = 'var(--tm-border)'"
            >
              <el-icon :size="30" style="color: var(--tm-accent)"><FolderOpened /></el-icon>
              <p class="text-[13px] text-text-2">点击选择日志文件夹</p>
              <p class="text-[11.5px] text-text-3">支持 .log / .txt / .out / .trace / .gc，会自动过滤非日志文件</p>
            </div>
          </template>

          <template v-else>
            <!-- 上传 + 解析进度（进度来自后端导入任务，不是前端动画） -->
            <div v-if="parsing" class="rounded-lg border border-border bg-surface p-4">
              <div class="mb-2 flex items-center justify-between text-[12.5px]">
                <span class="text-text-1">正在上传并解析日志…</span>
                <span class="mono text-text-3">
                  {{ parseTotal ? `${parsedCount} / ${parseTotal} 行` : '上传中…' }}
                </span>
              </div>
              <el-progress :percentage="Math.floor(parseProgress)" :stroke-width="8" />
            </div>

            <!-- 文件列表 + 表单 -->
            <template v-else>
              <div class="mb-3 rounded-lg border border-border bg-surface p-3">
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <el-icon :size="16" style="color: var(--tm-accent)"><FolderOpened /></el-icon>
                    <span class="mono text-[13px] font-medium text-text-1">{{ folderName }}/</span>
                  </div>
                  <el-button link size="small" @click="folderInput?.click()">重选文件夹</el-button>
                </div>
                <div class="mt-1 text-[11.5px] text-text-3">
                  {{ selectedFiles.length }} 个日志文件 · 共
                  {{ fmtSize(selectedFiles.reduce((s, f) => s + f.size, 0)) }}
                </div>
                <div class="mono mt-2 max-h-[104px] space-y-0.5 overflow-y-auto rounded px-2 py-1.5 text-[11px] text-text-3" style="background: var(--tm-code-bg)">
                  <div v-for="f in selectedFiles.slice(0, 50)" :key="f.name" class="flex justify-between gap-3">
                    <span class="truncate">{{ f.name }}</span>
                    <span class="shrink-0">{{ fmtSize(f.size) }}</span>
                  </div>
                  <div v-if="selectedFiles.length > 50" class="text-center">… 其余 {{ selectedFiles.length - 50 }} 个</div>
                </div>
              </div>

              <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" label-position="right">
                <el-form-item label="项目名" prop="name">
                  <el-input v-model="form.name" placeholder="默认取文件夹名，可修改" />
                </el-form-item>
                <el-form-item label="描述">
                  <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明项目用途（选填）" />
                </el-form-item>
                <el-form-item label="技术栈">
                  <el-input v-model="form.language" placeholder="Java / Spring Boot 3.2" />
                </el-form-item>
              </el-form>
            </template>
          </template>
        </div>

        <!-- 路径模式 -->
        <el-form v-if="mode === 'PATH'" ref="formRef" :model="form" :rules="rules" label-width="96px" label-position="right">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="如 order-service 订单中心" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话说明项目用途" />
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="form.status">
              <el-radio value="DEVELOPING">开发中（实时监听）</el-radio>
              <el-radio value="DEPLOYED">已上线（复盘分析）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="技术栈">
            <el-input v-model="form.language" placeholder="Java / Spring Boot 3.2" />
          </el-form-item>
          <el-form-item label="日志路径" prop="logPath">
            <el-input v-model="form.logPath" placeholder="/var/log/order-service/app.log" class="mono" />
          </el-form-item>
          <el-form-item label="健康端点">
            <el-input v-model="form.healthUrl" placeholder="http://127.0.0.1:8081/actuator/health（选填）" class="mono" />
          </el-form-item>
          <el-form-item label="源码根目录">
            <el-input v-model="form.sourceRoot" placeholder="/workspace/order-service/src/main/java（选填）" class="mono" />
          </el-form-item>
        </el-form>
      </template>

      <template #footer>
        <el-button :disabled="parsing" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="parsing" :disabled="mode === 'UPLOAD' && !editing && !selectedFiles.length" @click="submit">
          {{ mode === 'UPLOAD' && !editing ? '上传并解析' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
