<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { Promotion } from '@element-plus/icons-vue'
import { askFollowUp, type FollowUpHandle } from '@/api/diagnosis'
import type { ChatMessage, ChatStep, DiagnosisSession } from '@/types/diagnosis'
import { toolLabel } from '@/utils/toolMeta'

const props = defineProps<{
  sessionId: string
  session: DiagnosisSession | null
  /** 诊断是否已产出结论（未完成时禁用追问） */
  ready: boolean
}>()

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const listEl = ref<HTMLElement | null>(null)
let handle: FollowUpHandle | null = null

const suggestions = computed(() => {
  if (messages.value.length) return []
  const r = props.session?.result
  if (!r) return []
  return [
    `为什么是这个根因？`,
    `怎么修复这个问题？`,
    `给我看关键代码`,
    `置信度 ${(r.confidence * 100).toFixed(0)}% 是怎么评估的？`,
  ]
})

function scrollBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

watch(() => props.sessionId, () => {
  handle?.cancel()
  handle = null
  sending.value = false
  messages.value = []
})

function render(content: string) {
  return DOMPurify.sanitize(md.render(content))
}

function send(text?: string) {
  const question = (text ?? input.value).trim()
  if (!question || sending.value || !props.ready) return
  input.value = ''
  messages.value.push({
    id: `u-${Date.now()}`,
    role: 'user',
    content: question,
    ts: Date.now(),
  })
  messages.value.push({
    id: `a-${Date.now()}`,
    role: 'assistant',
    content: '',
    streaming: true,
    steps: [],
    ts: Date.now(),
  })
  /*
   * ★ 必须拿到 push 之后数组里那个【响应式代理】再来改内容。
   *   push 进去的是原始对象，直接 `reply.content += token` 改的是原始对象 ——
   *   没走 Proxy 的 set 钩子，不会触发依赖收集，
   *   表现就是"整段回答一次性蹦出来"，打字机效果失效。
   *   （以前没暴露是因为 LLM 侧没开流式，整段本来就在一帧里到达。）
   */
  const reply = messages.value[messages.value.length - 1]
  sending.value = true
  scrollBottom()

  handle = askFollowUp({
    sessionId: props.sessionId,
    session: props.session,
    question,
    history: messages.value.slice(0, -2),
    onToken: (token) => {
      reply.content += token
      scrollBottom()
    },
    onToolCall: (frame) => {
      const args = Object.entries(frame.args ?? {})
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(' · ')
      reply.steps = [...(reply.steps ?? []), { tool: frame.tool, args, state: 'running' }]
      scrollBottom()
    },
    onToolResult: (frame) => {
      const steps: ChatStep[] = reply.steps ?? []
      // 从后往前找同名的 running 步 —— 同一个工具可能被调多次，要配到最后那次
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].tool === frame.tool && steps[i].state === 'running') {
          steps[i] = {
            ...steps[i],
            state: 'done',
            summary: frame.summary,
            costMs: frame.costMs,
            truncated: frame.truncated,
          }
          break
        }
      }
      reply.steps = [...steps]
      scrollBottom()
    },
    onDone: () => {
      reply.streaming = false
      sending.value = false
      handle = null
      scrollBottom()
    },
    onError: (msg) => {
      reply.content = reply.content || `（追问失败：${msg}）`
      reply.streaming = false
      sending.value = false
      handle = null
    },
  })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

onBeforeUnmount(() => handle?.cancel())
</script>

<template>
  <section class="rounded-lg border border-border bg-surface">
    <!-- 标题栏 -->
    <div class="flex items-center gap-2 border-b border-border px-4 py-2.5">
      <span class="text-[12px] font-semibold uppercase tracking-wide text-text-3">向 AI 追问</span>
      <span
        v-if="!ready"
        class="rounded px-1.5 py-0.5 text-[11px]"
        style="color: var(--tm-text-3); background: var(--tm-code-bg)"
      >
        诊断完成后可继续对话
      </span>
      <span
        v-else-if="sending"
        class="flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px]"
        style="color: var(--tm-stage-3); background: var(--tm-code-bg)"
      >
        <span class="inline-block h-1 w-1 rounded-full" style="background: var(--tm-stage-3); animation: tm-chat-blink 1s steps(2) infinite" />
        AI 思考中
      </span>
    </div>

    <!-- 消息列表 -->
    <div ref="listEl" class="max-h-[340px] min-h-[96px] space-y-3 overflow-y-auto px-4 py-3">
      <!-- 空状态 -->
      <div v-if="!messages.length" class="py-4 text-center">
        <p class="text-[12.5px] text-text-3">
          对本次诊断结论有疑问？直接问我——我带着这个会话的完整上下文（触发日志、工具调用结果、证据链）。
        </p>
        <div v-if="suggestions.length" class="mt-3 flex flex-wrap justify-center gap-2">
          <button
            v-for="s in suggestions"
            :key="s"
            class="rounded-full border border-border px-3 py-1 text-[12px] text-text-2 transition-colors hover:border-accent hover:text-accent"
            @click="send(s)"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <template v-else>
        <div v-for="m in messages" :key="m.id" class="flex" :class="m.role === 'user' ? 'justify-end' : 'justify-start'">
          <!-- 用户气泡 -->
          <div
            v-if="m.role === 'user'"
            class="max-w-[85%] whitespace-pre-wrap rounded-lg px-3 py-2 text-[12.5px] leading-relaxed"
            style="background: var(--tm-accent); color: #fff"
          >
            {{ m.content }}
          </div>
          <!-- AI 气泡（markdown 渲染 + 流式光标） -->
          <div
            v-else
            class="chat-md max-w-[92%] rounded-lg border border-border px-3 py-2"
            style="background: var(--tm-bg)"
          >
            <!-- 本轮 Agent 调过的工具（追问接入 Agent 后才有；让用户知道它在干嘛） -->
            <div v-if="m.steps?.length" class="mb-1.5 space-y-1">
              <div
                v-for="(st, i) in m.steps"
                :key="i"
                class="flex items-center gap-1.5 rounded-sm px-2 py-1 text-[11.5px]"
                style="background: var(--tm-code-bg)"
                :title="st.summary || st.args || ''"
              >
                <span
                  class="h-1.5 w-1.5 shrink-0 rounded-full"
                  :style="{ background: st.state === 'running' ? 'var(--tm-status-running)' : 'var(--tm-status-success)' }"
                />
                <span class="shrink-0 text-text-2">{{ st.state === 'running' ? '正在' : '已' }}{{ toolLabel(st.tool) }}</span>
                <span v-if="st.args" class="mono min-w-0 truncate text-text-3">{{ st.args }}</span>
                <span v-if="st.costMs !== undefined" class="mono ml-auto shrink-0 text-text-3">{{ st.costMs }}ms</span>
              </div>
            </div>
            <div v-html="render(m.content)" />
            <span
              v-if="m.streaming"
              class="ml-0.5 inline-block h-[13px] w-[7px] align-middle"
              style="background: var(--tm-accent); animation: tm-chat-blink 0.8s steps(2) infinite"
            />
          </div>
        </div>
      </template>
    </div>

    <!-- 输入区 -->
    <div class="border-t border-border p-3">
      <div class="flex items-end gap-2">
        <textarea
          v-model="input"
          :rows="2"
          :disabled="!ready"
          :placeholder="ready ? '输入追问，Enter 发送，Shift+Enter 换行' : '等待诊断结论…'"
          class="flex-1 resize-none rounded-md border border-border bg-transparent px-3 py-2 text-[12.5px] text-text-1 outline-none transition-colors placeholder:text-text-3 focus:border-accent disabled:cursor-not-allowed disabled:opacity-50"
          @keydown="onKeydown"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :disabled="!ready || sending || !input.trim()"
          :loading="sending"
          @click="send()"
        >
          发送
        </el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
@keyframes tm-chat-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.chat-md :deep(p) { margin: 0.3em 0; font-size: 12.5px; line-height: 1.7; color: var(--tm-text-1); }
.chat-md :deep(p:first-child) { margin-top: 0; }
.chat-md :deep(p:last-child) { margin-bottom: 0; }
.chat-md :deep(ul), .chat-md :deep(ol) { padding-left: 1.4em; margin: 0.4em 0; font-size: 12.5px; color: var(--tm-text-1); }
.chat-md :deep(li) { margin: 0.2em 0; }
.chat-md :deep(strong) { color: var(--tm-text-1); }
.chat-md :deep(code) {
  font-family: var(--tm-font-mono); font-size: 11.5px;
  background: var(--tm-code-bg); padding: 1px 4px; border-radius: 4px; color: var(--tm-accent);
}
.chat-md :deep(pre) { background: var(--tm-code-bg); border: 1px solid var(--tm-border); border-radius: 6px; padding: 10px; overflow: auto; margin: 0.5em 0; }
.chat-md :deep(pre code) { background: transparent; padding: 0; color: var(--tm-text-1); }
</style>
