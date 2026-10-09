<script setup lang="ts">
import { watch, ref } from 'vue'
import EmptyState from './EmptyState.vue'

const props = defineProps<{
  loading?: boolean
  error?: string | null
  empty?: boolean
  emptyText?: string
  retry?: () => void
}>()

/**
 * 骨架屏延迟策略：
 * - loading 持续超过 250ms 才显示骨架屏（快速加载完全不闪烁，内容不卸载，点击不落空）
 * - loading 结束立刻隐藏骨架屏并上屏数据，不再人为延迟
 * - 刷新期间保留旧内容（stale-while-revalidate），页面始终可交互
 */
const skeleton = ref(true)
let timer: ReturnType<typeof setTimeout> | null = null
watch(
  () => props.loading,
  (v) => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (v) {
      timer = setTimeout(() => (skeleton.value = true), 250)
    } else {
      skeleton.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <div v-if="loading || skeleton" class="space-y-2.5 py-2" aria-busy="true">
    <div class="skeleton h-5 w-2/5 rounded-sm" />
    <div class="skeleton h-5 w-4/5 rounded-sm" />
    <div class="skeleton h-5 w-3/5 rounded-sm" />
    <div class="skeleton h-5 w-4/6 rounded-sm" />
    <div class="skeleton h-5 w-1/2 rounded-sm" />
  </div>
  <div
    v-else-if="error"
    class="flex flex-col items-center gap-3 rounded-lg border border-[var(--tm-status-failed)]/30 bg-[var(--tm-status-failed)]/5 py-10"
  >
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="var(--tm-status-failed)" stroke-width="1.8" stroke-linecap="round">
      <circle cx="12" cy="12" r="9" /><path d="M12 8v5m0 3h.01" />
    </svg>
    <p class="text-[13.5px] text-text-2">{{ error }}</p>
    <el-button v-if="retry" size="small" @click="retry">重试</el-button>
  </div>
  <EmptyState v-else-if="empty" :text="emptyText" />
  <slot v-else />
</template>

<style scoped>
.skeleton {
  background: linear-gradient(90deg, var(--tm-code-bg) 25%, var(--tm-border) 50%, var(--tm-code-bg) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite linear;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
