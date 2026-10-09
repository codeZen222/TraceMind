<script setup lang="ts">
import { computed } from 'vue'
import type { DiagnosisSession } from '@/types/diagnosis'
import { useLogFormat } from '@/composables/useLogFormat'
import { projectColor } from '@/utils/projectColor'

const props = defineProps<{
  sessions: DiagnosisSession[]
  currentId?: string | null
}>()
const emit = defineEmits<{ select: [sessionId: string] }>()
/* logTitle 统一在 useLogFormat 里实现：工作台的「最近诊断会话」用的是同一个，
   两处各写一份必然会慢慢长歪 */
const { rel, cost, logTitle } = useLogFormat()

function statusColor(s: DiagnosisSession['status']) {
  return {
    RUNNING: 'var(--tm-status-running)',
    SUCCEEDED: 'var(--tm-status-success)',
    FAILED: 'var(--tm-status-failed)',
    DEGRADED: 'var(--tm-level-warn)',
  }[s]
}

function statusText(s: DiagnosisSession['status']) {
  return { RUNNING: '进行中', SUCCEEDED: '已完成', FAILED: '失败', DEGRADED: '已降级运行' }[s]
}

/* 进行中置顶，其余按开始时间倒序 */
const STATUS_RANK: Record<DiagnosisSession['status'], number> = {
  RUNNING: 0,
  DEGRADED: 1,
  FAILED: 2,
  SUCCEEDED: 3,
}

function sortSessions(list: DiagnosisSession[]) {
  return [...list].sort((a, b) => {
    const r = STATUS_RANK[a.status] - STATUS_RANK[b.status]
    if (r !== 0) return r
    return new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()
  })
}

/**
 * 按项目分组：一组 = 一个项目。
 * 组间按「组内最新会话时间」倒序，保证刚跑过的项目排在最上面。
 */
const groups = computed(() => {
  const map = new Map<string, { projectId: string; projectName: string; sessions: DiagnosisSession[] }>()
  for (const s of props.sessions) {
    let g = map.get(s.projectId)
    if (!g) {
      g = { projectId: s.projectId, projectName: s.projectName, sessions: [] }
      map.set(s.projectId, g)
    }
    g.sessions.push(s)
  }
  const arr = [...map.values()]
  for (const g of arr) g.sessions = sortSessions(g.sessions)
  return arr.sort((a, b) => {
    const at = Math.max(...a.sessions.map((s) => new Date(s.startedAt).getTime()))
    const bt = Math.max(...b.sessions.map((s) => new Date(s.startedAt).getTime()))
    return bt - at
  })
})

/** 只有当列表里确实存在多个项目时才显示分组头，单选一个项目时不冗余 */
const showHeaders = computed(() => groups.value.length > 1)

function shortId(id: string) {
  return id.replace(/^sess-/, '#')
}

/**
 * 会话标题 = 触发日志的异常特征。
 * 项目名已由分组头承载，卡片再重复项目名就只剩噪音，所以这里回答的是用户真正关心的
 * 「这次报的是什么错」：优先取异常类名（辨识度最高），退化到 Drain3 模板。
 *
 * ★ 全程必须容忍 null：`template` / `clusterId` 来自 /v1/parse（Drain3 模板提取），
 *   那条链路失败时就是 null；触发日志本身也可能不含异常名（比如触发的是普通 WARN 行）。
 *   这里一旦抛异常，整张卡片渲染失败 —— 表现是"会话列表一片空白"，
 *   用户会以为是筛选坏了，其实只是这一张卡片崩了。
 */
</script>

<template>
  <div class="space-y-2">
    <div v-for="g in groups" :key="g.projectId">
      <!-- 项目分组头 -->
      <div
        v-if="showHeaders"
        class="mb-1.5 flex items-center gap-2 px-0.5"
        :class="groups[0] === g ? '' : 'pt-2'"
      >
        <span class="h-2 w-2 shrink-0 rounded-[2px]" :style="{ background: projectColor(g.projectId) }" />
        <span class="truncate text-[11.5px] font-semibold text-text-2" :title="g.projectName">
          {{ g.projectName }}
        </span>
        <span class="shrink-0 text-[10.5px] text-text-3">{{ g.sessions.length }} 条</span>
        <span class="h-px min-w-[8px] flex-1" style="background: var(--tm-border)" />
      </div>

      <div class="space-y-1.5">
        <button
          v-for="s in g.sessions"
          :key="s.sessionId"
          class="relative w-full overflow-hidden rounded-sm border p-2.5 pl-3 text-left transition-all duration-200 hover:border-accent"
          :class="s.sessionId === props.currentId ? 'border-accent' : 'border-border bg-surface'"
          :style="s.sessionId === props.currentId ? { background: 'var(--tm-accent-soft)' } : {}"
          @click="emit('select', s.sessionId)"
        >
          <!-- 左侧项目色条：同一项目的卡片颜色一致，扫一眼即可区分 -->
          <span
            class="absolute left-0 top-0 h-full w-[3px]"
            :style="{ background: projectColor(s.projectId) }"
          />

          <!-- 第一行：异常特征（主标识）+ 时间。项目已由分组头承载，不再重复 -->
          <div class="flex items-center gap-1.5">
            <span
              class="h-1.5 w-1.5 shrink-0 rounded-full"
              :style="{ background: statusColor(s.status) }"
            />
            <span
              class="min-w-0 flex-1 truncate text-[12.5px] font-semibold text-text-1"
              :title="s.triggerLog.rawText"
            >
              {{ logTitle(s.triggerLog) }}
            </span>
            <span class="mono shrink-0 text-[10.5px] text-text-3">{{ rel(s.startedAt) }}</span>
          </div>

          <!-- 第二行：状态 · 是否走 LLM · 耗时 -->
          <div class="mt-1 flex items-center gap-1.5 text-[11px]">
            <span class="shrink-0" :style="{ color: statusColor(s.status) }">{{ statusText(s.status) }}</span>
            <span
              class="shrink-0 rounded px-1 text-[10.5px]"
              :style="
                s.llmUsed
                  ? { color: 'var(--tm-stage-3)', background: 'var(--tm-code-bg)' }
                  : { color: 'var(--tm-stage-2)', background: 'var(--tm-code-bg)' }
              "
            >
              {{ s.llmUsed ? 'Agent' : '零 LLM' }}
            </span>
            <span class="mono ml-auto shrink-0 text-text-3">{{ cost(s.totalCostMs) }}</span>
          </div>

          <!-- 第三行：Drain3 模板簇 + 日志模板 + 会话号（次要信息，弱化处理） -->
          <div class="mt-0.5 flex items-center gap-2 text-[10.5px] text-text-3">
            <!-- 只有一个项目（无分组头）时，项目名降级到这里作为身份提示 -->
            <span v-if="!showHeaders" class="flex min-w-0 shrink-0 items-center gap-1">
              <span
                class="h-1.5 w-1.5 shrink-0 rounded-[2px]"
                :style="{ background: projectColor(s.projectId) }"
              />
              <span class="max-w-[104px] truncate">{{ s.projectName }}</span>
            </span>
            <span class="mono min-w-0 truncate" :title="`${s.triggerLog.clusterId} · ${s.triggerLog.template}`">
              {{ s.triggerLog.clusterId }} · {{ s.triggerLog.template }}
            </span>
            <span
              class="mono ml-auto max-w-[96px] shrink-0 truncate"
              :title="s.sessionId"
            >
              {{ shortId(s.sessionId) }}
            </span>
          </div>
        </button>
      </div>
    </div>
  </div>
</template>
