<script setup lang="ts">
import { computed } from 'vue'
import type { DiagnosisEvent } from '@/types/diagnosis'

const props = defineProps<{
  event: Extract<DiagnosisEvent, { type: 'rule_hit' }>
}>()

const analyzer = computed(() => props.event.analyzer)
</script>

<template>
  <div
    class="rounded-lg border-2 p-4"
    style="border-color: var(--tm-accent); background: var(--tm-accent-soft)"
  >
    <div class="flex items-center gap-2">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--tm-accent)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M13 2 3 14h7l-1 8 10-12h-7l1-8z" />
      </svg>
      <span class="text-[13.5px] font-semibold" style="color: var(--tm-accent)">规则命中 · 零 LLM 成本</span>
      <span class="mono ml-auto text-[11.5px] text-text-3">{{ event.costMs }}ms</span>
    </div>
    <div class="mono mt-2.5 text-[12.5px] text-text-2">
      命中规则：<span style="color: var(--tm-accent)">{{ analyzer }}</span>
    </div>
    <p class="mono mt-2 whitespace-pre-wrap text-[12.5px] leading-relaxed text-text-1">{{ event.solution }}</p>
  </div>
</template>
