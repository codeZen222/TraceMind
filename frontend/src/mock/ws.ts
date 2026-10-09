import type { WsMessage } from '@/types/diagnosis'
import { db } from './db'

export interface MockSocketHandlers {
  onOpen: () => void
  onMessage: (m: WsMessage) => void
  onClose: () => void
}

/**
 * Mock WebSocket：按 seq 每 120~300ms 推送一条事件，
 * 模拟真实后端的诊断实时流。行为与第 5.4 节协议一致。
 */
export function openMockDiagnosisSocket(sessionId: string, handlers: MockSocketHandlers) {
  let closed = false
  const timers: ReturnType<typeof setTimeout>[] = []

  const session = db.sessions.find((s) => s.sessionId === sessionId)
  if (!session) {
    timers.push(setTimeout(() => handlers.onClose(), 100))
    return { close: () => undefined }
  }

  timers.push(
    setTimeout(() => {
      if (closed) return
      handlers.onOpen()
      session.events.forEach((msg, i) => {
        timers.push(
          setTimeout(() => {
            if (closed) return
            handlers.onMessage(msg)
            // 终态事件推完即服务端主动关闭
            if (msg.event.type === 'result' || msg.event.type === 'error') {
              closed = true
              // 后端侧标记完成
              session.status = 'SUCCEEDED'
              session.finishedAt = new Date().toISOString()
              session.totalCostMs = session.llmUsed ? 4820 : 28
              session.result =
                msg.event.type === 'result'
                  ? {
                      rootCause: msg.event.rootCause,
                      evidence: msg.event.evidence,
                      suggestion: msg.event.suggestion,
                      confidence: msg.event.confidence,
                      toolCallCount: session.events.filter(
                        (e) => e.event.type === 'tool_call',
                      ).length,
                      relatedLogIds: [],
                    }
                  : null
              handlers.onClose()
            }
          }, (i + 1) * (120 + Math.random() * 180)),
        )
      })
    }, 200),
  )

  return {
    close() {
      closed = true
      timers.forEach(clearTimeout)
    },
  }
}
