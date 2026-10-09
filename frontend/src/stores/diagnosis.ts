import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { WsMessage, DiagnosisStatus, DiagnosisResult, DiagnosisEvent } from '@/types/diagnosis'
import { getDiagnosisSession, getWsTicket } from '@/api/diagnosis'
import { openMockDiagnosisSocket } from '@/mock/ws'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const MAX_RETRY = 6

interface SocketLike {
  close: () => void
}

export const useDiagnosisStore = defineStore('diagnosis', () => {
  const sessionId = ref<string | null>(null)
  const status = ref<DiagnosisStatus | null>(null)
  const events = ref<WsMessage[]>([])          // 已按 seq 排序
  const maxSeq = ref(0)
  const wsState = ref<'idle' | 'connecting' | 'open' | 'closed' | 'error'>('idle')
  const retryCount = ref(0)
  const result = ref<DiagnosisResult | null>(null)
  const notFound = ref(false)
  /** 重连成功后补齐的事件数（用于绿色提示条） */
  const recoveredCount = ref(0)

  // WebSocket / Mock 实例：非 reactive，组件只消费状态
  let sock: SocketLike | null = null
  let retryTimers: ReturnType<typeof setTimeout>[] = []
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  let lastPongAt = 0

  const isTerminal = computed(() => status.value === 'SUCCEEDED' || status.value === 'FAILED' || status.value === 'DEGRADED')

  function reset() {
    events.value = []
    maxSeq.value = 0
    result.value = null
    retryCount.value = 0
    recoveredCount.value = 0
    notFound.value = false
  }

  function clearTimers() {
    retryTimers.forEach(clearTimeout)
    retryTimers = []
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }

  /** 连接入口：先拉 REST（补历史 / 恢复会话），运行中则开 WS */
  let connectSeq = 0 // 竞态守卫：快速切换会话时，迟到的旧响应直接作废
  async function connect(id: string) {
    const token = ++connectSeq
    close(false)
    reset()
    sessionId.value = id
    wsState.value = 'connecting'
    try {
      const session = await getDiagnosisSession(id)
      if (token !== connectSeq) return // 用户已切到别的会话，丢弃本次结果
      if (!session) {
        notFound.value = true
        wsState.value = 'error'
        return
      }
      status.value = session.status
      // 已落地的事件直接回放（刷新页面不丢链路）
      if (session.events.length) {
        for (const m of session.events) append(m)
      }
      if (session.status !== 'RUNNING') {
        wsState.value = 'closed'
        return
      }
      openSocket(id)
    } catch (e) {
      if (token !== connectSeq) return
      notFound.value = true
      wsState.value = 'error'
    }
  }

  async function openSocket(id: string) {
    wsState.value = 'connecting'
    if (USE_MOCK) {
      const mock = openMockDiagnosisSocket(id, {
        onOpen: () => {
          wsState.value = 'open'
          retryCount.value = 0
          if (recoveredCount.value > 0) {
            const n = recoveredCount.value
            setTimeout(() => (recoveredCount.value = 0), 3000)
          }
        },
        onMessage: (m) => append(m),
        onClose: () => {
          wsState.value = 'closed'
        },
      })
      sock = { close: () => mock.close() }
      return
    }
    // ★ 多租户：先拿一次性 ticket 再连 WS。后端 enable-auth=true 时，没有有效 ticket 直接拒握手。
    let ticket = ''
    try {
      const res = await getWsTicket(id)
      ticket = res.ticket
    } catch {
      wsState.value = 'error'
      return
    }
    const ws = new WebSocket(
      `${import.meta.env.VITE_WS_BASE}/ws/diagnosis?sessionId=${id}&ticket=${encodeURIComponent(ticket)}`,
    )
    sock = {
      close: () => ws.close(),
    }
    ws.onopen = () => {
      wsState.value = 'open'
      retryCount.value = 0
      lastPongAt = Date.now()
      startHeartbeat()
    }
    ws.onmessage = (raw) => {
      try {
        const msg = JSON.parse(String(raw.data)) as WsMessage | { type: 'pong' }
        if ('type' in msg && (msg as { type: string }).type === 'pong') {
          lastPongAt = Date.now()
          return
        }
        append(msg as WsMessage)
      } catch {
        /* 忽略无法解析的帧 */
      }
    }
    ws.onclose = () => {
      stopHeartbeat()
      if (status.value === 'RUNNING' && retryCount.value < MAX_RETRY) {
        scheduleReconnect(id)
      } else {
        wsState.value = 'closed'
      }
    }
    ws.onerror = () => {
      wsState.value = 'error'
    }
  }

  function startHeartbeat() {
    heartbeatTimer = setInterval(() => {
      if (!sock) return
      if (Date.now() - lastPongAt > 15_000) {
        sock.close()
        return
      }
      // ping 由原生 ws 发送；mock 无需心跳
    }, 30_000)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }

  /** 指数退避重连：1/2/4/8s…上限 30s，最多 6 次 */
  function scheduleReconnect(id: string) {
    retryCount.value += 1
    const delay = Math.min(1000 * 2 ** (retryCount.value - 1), 30_000)
    wsState.value = 'connecting'
    retryTimers.push(setTimeout(() => openSocket(id), delay))
  }

  /** seq 去重 + 缺口补偿 + 事件副作用 */
  function append(m: WsMessage) {
    if (m.seq <= maxSeq.value) return // 去重
    if (m.seq > maxSeq.value + 1) {
      // 缺口：拉 REST 全量补齐
      void backfill(m.sessionId)
      return
    }
    events.value = [...events.value, m]
    maxSeq.value = m.seq
    handleEvent(m.event)
    // 防爆：保留最近 2000 条
    if (events.value.length > 2000) {
      events.value = events.value.slice(-2000)
    }
  }

  async function backfill(id: string) {
    try {
      const session = await getDiagnosisSession(id)
      if (!session) return
      const before = events.value.length
      for (const m of session.events) {
        if (m.seq > maxSeq.value) {
          events.value.push(m)
          maxSeq.value = Math.max(maxSeq.value, m.seq)
          handleEvent(m.event)
        }
      }
      events.value.sort((a, b) => a.seq - b.seq)
      recoveredCount.value += events.value.length - before
    } catch {
      /* 补齐失败：保留现有，等重连 */
    }
  }

  function handleEvent(event: DiagnosisEvent) {
    switch (event.type) {
      case 'result':
        result.value = {
          rootCause: event.rootCause,
          evidence: event.evidence,
          suggestion: event.suggestion,
          confidence: event.confidence,
          toolCallCount: events.value.filter((m) => m.event.type === 'tool_call').length,
          relatedLogIds: [],
        }
        status.value = 'SUCCEEDED'
        // 终态事件到达 → 主动关闭连接（不常驻）
        setTimeout(() => close(true), 300)
        break
      case 'error':
        status.value = status.value === 'DEGRADED' ? 'DEGRADED' : 'FAILED'
        setTimeout(() => close(true), 300)
        break
      default:
        break
    }
  }

  /** 组件卸载必须调用，避免连接泄漏 */
  function close(final = true) {
    clearTimers()
    sock?.close()
    sock = null
    if (final) wsState.value = 'closed'
  }

  return {
    sessionId, status, events, maxSeq, wsState, retryCount, result, notFound, recoveredCount,
    isTerminal,
    connect, append, reconnect: () => sessionId.value && connect(sessionId.value), close,
  }
})
