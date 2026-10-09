<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { WsMessage, DiagnosisEvent } from '@/types/diagnosis'
import RuleHitCard from './RuleHitCard.vue'
import ToolCallCard from './ToolCallCard.vue'
import TokenStream from './TokenStream.vue'

type Item =
  | { kind: 'stage'; key: string; event: Extract<DiagnosisEvent, { type: 'stage' }> }
  | { kind: 'rule_hit'; key: string; event: Extract<DiagnosisEvent, { type: 'rule_hit' }> }
  | { kind: 'thinking'; key: string; event: Extract<DiagnosisEvent, { type: 'thinking' }> }
  | { kind: 'tool'; key: string; call: Extract<DiagnosisEvent, { type: 'tool_call' }>; result: Extract<DiagnosisEvent, { type: 'tool_result' }> | null }
  | { kind: 'tokens'; key: string; tokens: string[] }
  | { kind: 'error'; key: string; event: Extract<DiagnosisEvent, { type: 'error' }> }

const props = defineProps<{ events: WsMessage[] }>()

/** 把事件流折叠为渲染项：tool_call/tool_result 配对、连续 token 合并 */
const items = computed<Item[]>(() => {
  const out: Item[] = []
  // 尚未有 result 的 tool 项在 out 中的下标
  const openToolIdx: number[] = []
  let tokens: string[] | null = null

  for (const m of props.events) {
    const e = m.event
    switch (e.type) {
      case 'stage':
        tokens = null
        out.push({ kind: 'stage', key: `s${m.seq}`, event: e })
        break
      case 'rule_hit':
        tokens = null
        out.push({ kind: 'rule_hit', key: `r${m.seq}`, event: e })
        break
      case 'thinking':
        tokens = null
        out.push({ kind: 'thinking', key: `t${m.seq}`, event: e })
        break
      case 'tool_call':
        tokens = null
        out.push({ kind: 'tool', key: `tool-${m.seq}`, call: e, result: null })
        openToolIdx.push(out.length - 1)
        break
      case 'tool_result': {
        tokens = null
        // 配对到最近一个同名且尚无 result 的调用
        for (let j = openToolIdx.length - 1; j >= 0; j--) {
          const idx = openToolIdx[j]
          const it = out[idx]
          if (it.kind === 'tool' && it.call.tool === e.tool) {
            it.result = e
            openToolIdx.splice(j, 1)
            break
          }
        }
        break
      }
      case 'token':
        // 连续 token 合并为同一段（NEVER 每个 token 一层 DOM）
        if (tokens) tokens.push(e.content)
        else {
          tokens = [e.content]
          out.push({ kind: 'tokens', key: `tok-${m.seq}`, tokens })
        }
        break
      case 'error':
        out.push({ kind: 'error', key: `e${m.seq}`, event: e })
        break
      case 'result':
        break // 结论在右栏 ConclusionCard 渲染
    }
  }
  return out
})

/* ------- 自动滚动与「回到最新」 ------- */
const scrollRef = ref<HTMLElement | null>(null)
const autoScroll = ref(true)
const missed = ref(0)
let pause = false

function onScroll() {
  const el = scrollRef.value
  if (!el) return
  const bottom = el.scrollHeight - el.scrollTop - el.clientHeight
  autoScroll.value = bottom < 100
  if (autoScroll.value) missed.value = 0
  pause = !autoScroll.value
}

watch(
  () => props.events.length,
  async (n, old) => {
    await nextTick()
    if (autoScroll.value && !pause) {
      scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight })
    } else if (n > (old ?? 0)) {
      missed.value += n - (old ?? 0)
    }
  },
)

onBeforeUnmount(() => {
  pause = false
})

function backToLatest() {
  scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' })
  autoScroll.value = true
  missed.value = 0
}
</script>

<template>
  <div class="relative">
    <div
      ref="scrollRef"
      class="space-y-3 overflow-y-auto pr-1"
      style="max-height: calc(100vh - 320px); min-height: 360px"
      @scroll="onScroll"
    >
      <template v-for="item in items" :key="item.key">
        <!-- 阶段分隔条 -->
        <div v-if="item.kind === 'stage'" class="flex items-center gap-3 pt-1">
          <span
            class="mono rounded-full border px-2.5 py-0.5 text-[11px] font-medium"
            :style="{
              color: `var(--tm-stage-${item.event.stage})`,
              borderColor: `var(--tm-stage-${item.event.stage})`,
              background: item.event.status === 'done' ? `var(--tm-stage-${item.event.stage}22, rgba(0,0,0,0.03))` : 'transparent',
            }"
          >
            阶段 {{ item.event.stage }} · {{ item.event.name }}
            <template v-if="item.event.status === 'done' && item.event.costMs !== undefined"> · {{ item.event.costMs }}ms</template>
          </span>
          <span v-if="item.event.status === 'running'" class="flex items-center gap-1.5 text-[11.5px]" style="color: var(--tm-status-running)">
            <span class="inline-block h-1.5 w-1.5 rounded-full" style="background: var(--tm-status-running); animation: soft 1.2s infinite" />
            执行中
          </span>
          <span class="h-px flex-1" :style="{ background: `var(--tm-stage-${item.event.stage})`, opacity: 0.35 }" />
        </div>

        <!-- 阶段2 规则命中 -->
        <RuleHitCard v-else-if="item.kind === 'rule_hit'" :event="item.event" />

        <!-- Agent 推理（可折叠） -->
        <details v-else-if="item.kind === 'thinking'" class="group rounded-sm border border-border bg-surface">
          <summary class="flex cursor-pointer select-none items-center gap-2 px-3 py-1.5 text-[12px] text-text-2">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="var(--tm-stage-3)" stroke-width="2" stroke-linecap="round"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z" /></svg>
            Agent 推理
            <span class="ml-auto text-[10.5px] text-text-3 group-open:hidden">展开</span>
          </summary>
          <p class="mono border-t border-border px-3 py-2 text-[12px] leading-relaxed text-text-2">{{ item.event.content }}</p>
        </details>

        <!-- 工具调用卡片 -->
        <ToolCallCard v-else-if="item.kind === 'tool'" :call="item.call" :result="item.result" />

        <!-- LLM 流式输出（打字机，token 合并） -->
        <TokenStream v-else-if="item.kind === 'tokens'" :tokens="item.tokens" />

        <!-- 错误条 -->
        <div v-else-if="item.kind === 'error'" class="flex items-start gap-2 rounded-sm border p-3" style="border-color: var(--tm-status-failed); background: rgba(229, 83, 75, 0.07)">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--tm-status-failed)" stroke-width="2" stroke-linecap="round" class="mt-px shrink-0">
            <path d="M12 9v4m0 4h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          </svg>
          <div>
            <p class="text-[12.5px] font-medium" style="color: var(--tm-status-failed)">阶段 {{ item.event.stage }} 执行出错</p>
            <p class="mono mt-1 text-[12px] text-text-2">{{ item.event.message }}</p>
          </div>
        </div>
      </template>

      <!-- 终点分隔 -->
      <div v-if="items.some((i) => i.kind === 'tokens') || items.length" class="flex items-center gap-3 pb-2">
        <span class="h-px flex-1 bg-border" />
        <span class="mono text-[11px] text-text-3">— 事件流结束 —</span>
        <span class="h-px flex-1 bg-border" />
      </div>
    </div>

    <!-- 回到最新 -->
    <transition
      enter-active-class="transition-all duration-200"
      enter-from-class="translate-y-2 opacity-0"
      enter-to-class="translate-y-0 opacity-100"
      leave-active-class="transition-all duration-200"
      leave-to-class="translate-y-2 opacity-0"
    >
      <button
        v-if="missed > 0"
        class="absolute bottom-3 left-1/2 flex -translate-x-1/2 items-center gap-1.5 rounded-full border border-border bg-surface px-3 py-1.5 text-[12px] shadow-sm hover:border-accent hover:text-accent"
        @click="backToLatest"
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 5v14m7-7-7 7-7-7" /></svg>
        回到最新（{{ missed }} 条新消息）
      </button>
    </transition>
  </div>
</template>

<style scoped>
@keyframes soft {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
