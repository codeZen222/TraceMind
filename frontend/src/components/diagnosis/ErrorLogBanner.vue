<script setup lang="ts">
import type { LogEntry } from '@/types/log'
import LevelTag from '@/components/common/LevelTag.vue'
import { useLogFormat } from '@/composables/useLogFormat'

defineProps<{ log: LogEntry }>()
const { abs, rel } = useLogFormat()
</script>

<template>
  <div class="rounded-sm border-l-[3px] border border-border bg-surface p-3" style="border-left-color: var(--tm-level-error)">
    <div class="flex flex-wrap items-center gap-2.5">
      <LevelTag :level="log.level" />
      <span class="mono text-[11.5px] text-text-3" :title="abs(log.timestamp)">{{ rel(log.timestamp) }}</span>
      <span class="mono rounded border border-border px-1.5 text-[11px] text-text-3" :title="`Drain3 模板簇：${log.template}`">
        {{ log.clusterId }}
      </span>
      <span class="mono ml-auto text-[11px] text-text-3">{{ log.filePath }}:{{ log.lineNumber }}</span>
    </div>
    <p class="mono mt-2 whitespace-pre-wrap break-words text-[12.5px] text-text-1">{{ log.rawText }}</p>
    <p v-if="log.stackTrace" class="mono mt-1 truncate text-[11.5px] text-text-3" :title="log.stackTrace">
      堆栈 {{ log.stackTrace.split('\n').length }} 行
    </p>
  </div>
</template>
