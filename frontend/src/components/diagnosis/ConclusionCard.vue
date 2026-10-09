<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DiagnosisResult } from '@/types/diagnosis'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useLogFormat } from '@/composables/useLogFormat'

const props = defineProps<{
  result: DiagnosisResult
  llmUsed: boolean
  matchedAnalyzer: string | null
  totalCostMs: number | null
}>()

const { cost } = useLogFormat()
const confidencePct = computed(() => Math.round(props.result.confidence * 100))

const copied = ref(false)
async function copyConclusion() {
  const text = [
    `【根因】${props.result.rootCause}`,
    '【证据链】',
    ...props.result.evidence.map((e, i) => `${i + 1}. ${e}`),
    `【修复建议】${props.result.suggestion}`,
    `【置信度】${confidencePct.value}%`,
  ].join('\n')
  await navigator.clipboard.writeText(text)
  copied.value = true
  ElMessage.success({ message: '结论已复制', duration: 1200 })
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="rounded-lg border p-4 transition-all duration-200" :style="{ borderColor: 'var(--tm-status-success)', background: 'var(--tm-surface)' }">
    <div class="flex items-center gap-2">
      <span class="flex h-6 w-6 items-center justify-center rounded-full" style="background: rgba(63, 160, 107, 0.15)">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--tm-status-success)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5" /></svg>
      </span>
      <span class="text-[13.5px] font-semibold">诊断结论</span>
      <button
        class="ml-auto flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-[11px] text-text-3 hover:border-accent hover:text-accent"
        @click="copyConclusion"
      >
        <el-icon :size="11"><CopyDocument /></el-icon>{{ copied ? '已复制' : '复制结论' }}
      </button>
    </div>

    <div class="mt-3 space-y-4">
      <section>
        <h4 class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">根因</h4>
        <p class="text-[13px] leading-relaxed text-text-1">{{ result.rootCause }}</p>
      </section>

      <section>
        <h4 class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">证据链</h4>
        <ol class="space-y-1.5">
          <li v-for="(e, i) in result.evidence" :key="i" class="flex gap-2 text-[12.5px] text-text-2">
            <span class="mono mt-px shrink-0 text-accent">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="leading-relaxed">{{ e }}</span>
          </li>
        </ol>
      </section>

      <section>
        <h4 class="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-text-3">修复建议</h4>
        <p class="text-[13px] leading-relaxed text-text-1">{{ result.suggestion }}</p>
      </section>

      <section>
        <div class="mb-1.5 flex items-center justify-between text-[12px]">
          <span class="font-semibold uppercase tracking-wide text-text-3">置信度</span>
          <span class="mono text-accent">{{ confidencePct }}%</span>
        </div>
        <div class="h-1.5 overflow-hidden rounded-full bg-[var(--tm-code-bg)]">
          <div
            class="h-full rounded-full transition-all duration-500"
            :style="{ width: `${confidencePct}%`, background: 'var(--tm-accent)' }"
          />
        </div>
      </section>
    </div>
  </div>
</template>
