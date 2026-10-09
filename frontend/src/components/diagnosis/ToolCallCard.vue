<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DiagnosisEvent } from '@/types/diagnosis'
import { Search, Collection, Postcard, DataAnalysis, Document, Odometer } from '@element-plus/icons-vue'
import { toolLabel } from '@/utils/toolMeta'

const props = defineProps<{
  call: Extract<DiagnosisEvent, { type: 'tool_call' }>
  result: Extract<DiagnosisEvent, { type: 'tool_result' }> | null
}>()

/** 中文名统一走 toolMeta（追问气泡共用），这里只补每个工具专属的图标 */
const ICONS: Record<string, unknown> = {
  search_logs: Search,
  search_similar_cases: Collection,
  match_exception_rules: Postcard,
  count_error_frequency: DataAnalysis,
  read_source_code: Document,
  check_project_health: Odometer,
}

const expanded = ref(true)
const flash = ref(false)

// result 注入时展开并高亮 300ms
watch(
  () => props.result,
  (v) => {
    if (v) {
      expanded.value = true
      flash.value = true
      setTimeout(() => (flash.value = false), 300)
    }
  },
)

const meta = computed(() => ({ label: toolLabel(props.call.tool), icon: ICONS[props.call.tool] ?? Search }))

const argsSummary = computed(() =>
  Object.entries(props.call.args)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(' · '),
)
</script>

<template>
  <div
    class="rounded-sm border bg-surface transition-all duration-200"
    :class="flash ? 'border-accent' : 'border-border'"
    :style="flash ? { boxShadow: 'var(--tm-accent-soft) 0 0 0 3px' } : {}"
  >
    <button
      class="flex w-full items-center gap-2 px-3 py-2 text-left"
      @click="expanded = !expanded"
    >
      <el-icon :size="14" class="shrink-0 text-accent"><component :is="meta.icon" /></el-icon>
      <span class="text-[12.5px] font-medium text-text-1">工具调用 · {{ meta.label }}</span>
      <span class="mono hidden truncate text-[11.5px] text-text-3 md:inline md:max-w-[260px]">{{ argsSummary }}</span>
      <el-icon class="ml-auto shrink-0 text-text-3 transition-transform duration-200" :class="expanded ? 'rotate-90' : ''">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="m9 18 6-6-6-6" /></svg>
      </el-icon>
    </button>
    <div v-show="expanded" class="border-t border-border px-3 pb-2.5 pt-2">
      <div class="mono text-[11.5px] text-text-3">入参 {{ argsSummary }}</div>
      <template v-if="result">
        <div class="mono mt-1.5 whitespace-pre-wrap rounded-sm bg-[var(--tm-code-bg)] p-2.5 text-[12px] text-text-2">
          {{ result.summary }}
        </div>
        <div class="mt-1.5 flex items-center gap-2 text-[11px] text-text-3">
          <span class="mono">{{ result.costMs }}ms</span>
          <span
            v-if="result.truncated"
            class="rounded border border-border px-1 py-px text-[10.5px]"
            style="color: var(--tm-level-warn); border-color: var(--tm-level-warn)"
          >输出已截断</span>
        </div>
      </template>
      <div v-else class="mono mt-1.5 flex items-center gap-1.5 text-[11.5px]" style="color: var(--tm-status-running)">
        <span class="inline-block h-1.5 w-1.5 rounded-full" style="background: var(--tm-status-running); animation: pulse-soft 1.2s infinite" />
        执行中…
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes pulse-soft {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
