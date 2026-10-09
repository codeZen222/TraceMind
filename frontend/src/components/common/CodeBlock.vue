<script setup lang="ts">
import { computed, ref } from 'vue'
import hljs from 'highlight.js'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const props = withDefaults(
  defineProps<{
    code: string
    language?: string
    maxHeight?: number
  }>(),
  { language: 'java', maxHeight: 320 },
)

const copied = ref(false)

const html = computed(() => {
  try {
    return hljs.highlight(props.code, { language: props.language, ignoreIllegals: true }).value
  } catch {
    return props.code.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] ?? c))
  }
})

async function copy() {
  await navigator.clipboard.writeText(props.code)
  copied.value = true
  ElMessage.success({ message: '已复制', duration: 1200 })
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="group relative overflow-hidden rounded-sm border border-border bg-[var(--tm-code-bg)]">
    <button
      class="absolute right-2 top-2 z-10 flex h-7 w-7 items-center justify-center rounded border border-border bg-surface text-text-3 transition-opacity group-hover:opacity-100"
      :class="copied ? 'opacity-100 text-accent' : 'opacity-0'"
      title="复制"
      @click="copy"
    >
      <el-icon :size="13"><CopyDocument /></el-icon>
    </button>
    <div class="mono overflow-auto p-3 text-text-2" :style="{ maxHeight: `${maxHeight}px` }">
      <pre class="m-0 whitespace-pre-wrap break-words"><code v-html="html" /></pre>
    </div>
  </div>
</template>
