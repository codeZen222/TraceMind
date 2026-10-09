<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'

const props = defineProps<{ tokens: string[] }>()

/** 全量目标文本（连续 token 合并到同一容器） */
const target = computed(() => props.tokens.join(''))

const revealed = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function ensureTick() {
  if (timer) return
  // 40 字/秒 打字机；新 token 到来直接补全由 target 驱动，不阻塞
  timer = setInterval(() => {
    if (revealed.value >= target.value.length) {
      stop()
      return
    }
    revealed.value += 1
  }, 25)
}
function stop() {
  if (timer) clearInterval(timer)
  timer = null
}

watch(target, (t) => {
  if (revealed.value < t.length) ensureTick()
}, { immediate: true })

onBeforeUnmount(stop)

const text = computed(() => target.value.slice(0, revealed.value))
const done = computed(() => revealed.value >= target.value.length && target.value.length > 0)

const copied = ref(false)
async function copy() {
  await navigator.clipboard.writeText(target.value)
  copied.value = true
  setTimeout(() => (copied.value = false), 1500)
}
</script>

<template>
  <div class="group relative">
    <div class="mono whitespace-pre-wrap break-words rounded-sm border border-border bg-[var(--tm-code-bg)] p-3 text-[12.5px] text-text-1">
      <span data-testid="token-text">{{ text }}</span><span v-if="!done" class="caret" />
    </div>
    <button
      v-if="done"
      class="absolute -bottom-2.5 right-2 flex h-5 items-center gap-1 rounded border border-border bg-surface px-1.5 text-[10.5px] text-text-3 opacity-0 transition-opacity group-hover:opacity-100"
      :class="{ 'opacity-100 text-accent': copied }"
      @click="copy"
    >
      <el-icon :size="11"><CopyDocument /></el-icon>复制
    </button>
  </div>
</template>

<style scoped>
.caret {
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 1px;
  vertical-align: text-bottom;
  background: var(--tm-accent);
  animation: blink 0.9s steps(1) infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
</style>
