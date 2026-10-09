<script setup lang="ts">
export interface StageState {
  id: 1 | 2 | 3
  name: string
  status: 'pending' | 'running' | 'done' | 'skipped'
  costMs?: number
  elapsed?: number // running 实时计时 ms
}

defineProps<{ stages: StageState[] }>()
</script>

<template>
  <div class="flex items-stretch gap-1.5" role="list" aria-label="三阶段诊断流水线">
    <div
      v-for="(s, i) in stages"
      :key="s.id"
      role="listitem"
      class="flex min-w-0 flex-1 items-center gap-2.5 rounded-sm border px-3 py-2.5 transition-all duration-200"
      :class="[
        s.status === 'pending' && 'border-border bg-transparent',
        s.status === 'running' && 'border-transparent',
        s.status === 'done' && 'border-transparent',
        s.status === 'skipped' && 'border-border border-dashed opacity-55',
      ]"
      :style="[
        s.status === 'running' ? { background: 'var(--tm-accent-soft)', borderColor: 'var(--tm-accent)' } : {},
        s.status === 'done' ? { background: 'var(--tm-accent-soft)', borderColor: 'var(--tm-status-success)' } : {},
      ]"
    >
      <span
        class="mono flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold"
        :style="{
          border: `1.5px solid var(--tm-stage-${s.id})`,
          color: `var(--tm-stage-${s.id})`,
          background: s.status === 'running' ? `var(--tm-stage-${s.id})` : 'transparent',
          animation: s.status === 'running' ? 'pulse-soft 1.6s ease-in-out infinite' : undefined,
        }"
      >
        <svg v-if="s.status === 'done'" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="var(--tm-status-success)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 6 9 17l-5-5" />
        </svg>
        <template v-else>{{ s.id }}</template>
      </span>
      <!--
        ★ 第一行只放阶段名，状态说明一律走第二行。
          之前「已跳过 · 规则命中」是拼在阶段名后面的，三张卡等宽，
          而第三张的说明文字比 "35ms" 长得多，于是被截成「LangGraph Agent 已被…」，
          连下面那行"零 LLM 成本"也一起挤没了。状态文案放在它自己的行里就不会互相抢宽度。
      -->
      <div class="min-w-0 flex-1">
        <div class="truncate text-[13px] font-medium" :class="s.status === 'skipped' ? 'text-text-3' : 'text-text-1'">
          {{ s.name }}
        </div>
        <div class="truncate text-[11.5px] text-text-3">
          <template v-if="s.status === 'done' && s.costMs !== undefined">
            <span class="mono">{{ s.costMs }}ms</span>
          </template>
          <template v-else-if="s.status === 'running'">
            <span class="mono">+{{ ((s.elapsed ?? 0) / 1000).toFixed(1) }}s</span>
          </template>
          <template v-else-if="s.status === 'skipped'">已跳过 · 规则命中（零 LLM）</template>
          <template v-else><span class="mono">等待中</span></template>
        </div>
      </div>
      <svg
        v-if="i < stages.length - 1"
        class="hidden shrink-0 text-text-3 md:block"
        width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
      >
        <path d="M5 12h14m-6-6 6 6-6 6" />
      </svg>
    </div>
  </div>
</template>

<style scoped>
@keyframes pulse-soft {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}
</style>
