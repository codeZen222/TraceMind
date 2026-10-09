import { get, post } from './request'
import type { PageResult } from '@/types/common'
import type { ChatFrame, ChatMessage, DiagnosisSession } from '@/types/diagnosis'

export function triggerDiagnosis(projectId: string, logId: string) {
  return post<{ sessionId: string }>('/api/diagnosis/trigger', { projectId, logId })
}

export function getDiagnosisSession(sessionId: string) {
  return get<DiagnosisSession>(`/api/diagnosis/${sessionId}`)
}

/**
 * 会话列表。
 *
 * ★ projectId 是【必填】—— 后端 DiagnosisController#sessions 签的是
 *   `@RequestParam String projectId`（没有 required=false），
 *   漏传会直接返回 code 40000「缺少必填参数: projectId」。
 *   这里就从类型上卡死，别让编辑器放过漏传（业务数据强制按项目过滤）。
 */
export function getDiagnosisSessions(query: { projectId?: string; page?: number; size?: number }) {
  return get<PageResult<DiagnosisSession>>('/api/diagnosis/sessions', query)
}

/**
 * 拿 WebSocket 握手的一次性 ticket。
 *
 * ★ 多租户：后端签发前会校验会话归属当前用户。前端连 WS 前必须先拿 ticket，
 *   后端 enable-auth=true 时会校验（一次性、60s TTL），否则任何人拿 sessionId 就能偷看诊断过程。
 */
export function getWsTicket(sessionId: string) {
  return post<{ ticket: string }>(`/api/diagnosis/${sessionId}/ws-ticket`)
}

/* ---------------- 人机追问：诊断结论后与 AI 继续对话 ---------------- */

export interface FollowUpHandle {
  cancel: () => void
}

export interface FollowUpOptions {
  sessionId: string
  session: DiagnosisSession | null   // 会话上下文（mock 回复引擎依赖）
  question: string
  history: ChatMessage[]
  onToken: (token: string) => void
  onDone: () => void
  onError: (message: string) => void
  /** Agent 要调工具了（可选）—— 页面据此显示"正在查日志…" */
  onToolCall?: (frame: Extract<ChatFrame, { type: 'tool_call' }>) => void
  /** 工具查完了（可选） */
  onToolResult?: (frame: Extract<ChatFrame, { type: 'tool_result' }>) => void
}

/**
 * 解析一帧的 payload。
 *
 * 兼容两种格式：新格式是 JSON 帧；老格式整段就是文本（JSON 解析失败即判定为老格式）。
 */
function parseFrame(payload: string): ChatFrame | null {
  if (!payload.startsWith('{')) return null
  try {
    const data = JSON.parse(payload) as ChatFrame
    return data && typeof data.type === 'string' ? data : null
  } catch {
    return null
  }
}

/**
 * 向 AI 追问。真实后端：POST /api/diagnosis/:sessionId/chat（SSE 流式）。
 * Mock 模式下由 mock/chat.ts 基于会话上下文生成关键词相关的回复并模拟流式输出。
 */
export function askFollowUp(opts: FollowUpOptions): FollowUpHandle {
  const { sessionId, session, question, history, onToken, onDone, onError, onToolCall, onToolResult } = opts

  if (import.meta.env.VITE_USE_MOCK === 'true') {
    let cancelled = false
    // 动态引入避免非 mock 构建打包 mock 层
    import('@/mock/chat').then(async ({ buildFollowUpReply, splitTokens }) => {
      const { db } = await import('@/mock/db')
      if (cancelled) return
      const target = session ?? db.sessions.find((s) => s.sessionId === sessionId) ?? null
      const reply = target
        ? buildFollowUpReply(target, question)
        : '会话上下文未找到，无法回答。'
      const tokens = splitTokens(reply)
      let i = 0
      const timer = setInterval(() => {
        if (cancelled) {
          clearInterval(timer)
          return
        }
        onToken(tokens[i])
        i += 1
        if (i >= tokens.length) {
          clearInterval(timer)
          onDone()
        }
      }, 28)
    }).catch((e) => onError(e instanceof Error ? e.message : '追问失败'))
    return {
      cancel: () => {
        cancelled = true
      },
    }
  }

  // 真实后端：SSE 流式
  const controller = new AbortController()
  ;(async () => {
    try {
      const res = await fetch(`/api/diagnosis/${sessionId}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({ question, history }),
      })
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        const lines = buf.split('\n')
        buf = lines.pop() ?? ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          /*
           * ★ 只去掉协议自带的那个空格，**绝不能整个 trim**。
           *   文本帧 content 的首尾空格是有意义的 —— 英文单词之间的分隔符就是它，
           *   代码块的缩进也是。老实现是 .trim()，之所以一直没暴露，
           *   是因为当时整段回答只在**一帧**里到达（LLM 侧没开流式），
           *   一旦真流式起来，`the order` 就会变成 `theorder`。
           */
          const payload = line.slice(5).replace(/^ /, '')
          if (payload === '[DONE]') {
            onDone()
            return
          }
          const frame = parseFrame(payload)
          if (!frame) {
            // 老格式：整帧就是一段文本
            onToken(payload)
            continue
          }
          if (frame.type === 'text') onToken(frame.content)
          else if (frame.type === 'tool_call') onToolCall?.(frame)
          else if (frame.type === 'tool_result') onToolResult?.(frame)
        }
      }
      onDone()
    } catch (e) {
      if ((e as Error).name !== 'AbortError') onError(e instanceof Error ? e.message : '追问失败')
    }
  })()
  return { cancel: () => controller.abort() }
}
