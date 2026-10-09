<script setup lang="ts">
import { computed } from 'vue'
import type { LogLevel } from '@/types/log'

const props = defineProps<{ level: LogLevel }>()

const color = computed(() => ({
  ERROR: 'var(--tm-level-error)',
  WARN: 'var(--tm-level-warn)',
  INFO: 'var(--tm-level-info)',
  DEBUG: 'var(--tm-level-debug)',
  TRACE: 'var(--tm-level-trace)',
}[props.level]))
</script>

<template>
  <span
    class="mono inline-flex h-[20px] min-w-[46px] items-center justify-center rounded border px-1 text-[11px] font-semibold uppercase leading-none"
    :style="{ color: color, borderColor: color, background: 'transparent' }"
  >
    {{ level }}
  </span>
</template>
