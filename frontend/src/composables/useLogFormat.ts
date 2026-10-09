import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import type { LogEntry, LogLevel } from '@/types/log'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const EXCEPTION_RE = /([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*Exception)\b/
const CAUSE_RE = /,?\s*cause\s*=/i
const TITLE_MAX = 46

/** logger 之后的业务消息（Spring 的 pattern 用 " : " 分隔），并砍掉 cause= 后面的异常细节 */
function messageOf(raw: string): string {
  const idx = raw.indexOf(' : ')
  const msg = idx >= 0 ? raw.slice(idx + 3) : raw
  return msg.split(CAUSE_RE)[0].replace(/\s+/g, ' ').trim()
}

/**
 * 诊断会话的标题 = 触发日志的「错误特征」。
 *
 * 固定成同一个形状，避免同一个列表里出现多种粒度：
 *   ① 业务消息 + ` · ` + 异常类名
 *   ② 只有业务消息 / 只有异常类名
 *   ③ 都没有 → Drain3 模板 → 占位
 *
 * ★ 两条刻意的设计决定：
 *   1. **业务消息排在前面**：列表里的标题列只有 200 多像素宽，
 *      长异常类名（`SQLTransientConnectionException` 一个词就占满）会把后面的内容全挤掉。
 *      消息在前，窄列里才读得出"这次报的是哪件事"。
 *   2. **不整行照搬**：行首的时间、线程、logger 对"这是什么错"没有帮助，
 *      整行塞进去只会让每一行都不一样、横向没法扫。
 *
 * 全程容忍 null：template 来自 /v1/parse，那条链路挂了就是 null；
 * 这里一旦抛异常整张卡片都渲染不出来（表现是"列表一片空白"）。
 *
 * 提到 useLogFormat 里是为了让「工作台 · 最近诊断会话」和「诊断页 · 会话列表」
 * 对同一条会话显示同一个标题 —— 两处各写一份必然会慢慢长歪。
 */
export function logTitle(log?: LogEntry | null): string {
  const raw = log?.rawText ?? ''
  const msg = messageOf(raw)
  const ex = raw.match(EXCEPTION_RE)?.[1]?.split('.').pop()
  const text = msg ? (ex ? `${msg} · ${ex}` : msg) : (ex ?? '')
  if (text) return text.length > TITLE_MAX ? `${text.slice(0, TITLE_MAX)}…` : text
  const tpl = log?.template?.replace(/[\s:*]+$/, '').trim()
  if (tpl) return tpl.length > TITLE_MAX ? `${tpl.slice(0, TITLE_MAX)}…` : tpl
  return '（无日志内容）'
}

export function useLogFormat() {
  /** 相对时间（悬停 Tooltip 显示绝对时间用） */
  const rel = (iso: string) => dayjs(iso).fromNow()
  const abs = (iso: string) => dayjs(iso).format('YYYY-MM-DD HH:mm:ss.SSS')

  /** 毫秒格式化：<1000ms 显示 ms，否则显示 s（保留 1 位） */
  const cost = (ms?: number | null) => {
    if (ms === undefined || ms === null) return '--'
    return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
  }

  /** 字节格式化 */
  const bytes = (n: number) => {
    if (n < 1024) return `${n} B`
    if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`
    return `${(n / 1024 ** 2).toFixed(1)} MB`
  }

  /** 级别 → CSS 变量色（附录 B） */
  const levelColor = (level: LogLevel): string => ({
    ERROR: 'var(--tm-level-error)',
    WARN: 'var(--tm-level-warn)',
    INFO: 'var(--tm-level-info)',
    DEBUG: 'var(--tm-level-debug)',
    TRACE: 'var(--tm-level-trace)',
  }[level])

  const levelBg = (level: LogLevel): string =>
    ({ ERROR: 'rgba(229, 83, 75, 0.10)', WARN: 'rgba(217, 164, 65, 0.10)' } as Record<string, string>)[level] ?? 'transparent'

  /** 关键词高亮：先转义再包 <mark>，防 XSS */
  const highlight = (text: string, keyword?: string): string => {
    const esc = text.replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] ?? c),
    )
    if (!keyword) return esc
    const safe = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return esc.replace(new RegExp(safe, 'gi'), (m) => `<mark class="tm-keyword">${m}</mark>`)
  }

  /**
   * 文件路径中段省略。
   *
   * ★ 必须容忍 p 为 null / undefined：这个函数是在模板里直接渲染的，
   *   一旦 p 为空就抛 TypeError，而 Vue dev 模式会把渲染错误重新抛出 →
   *   正好在 <RouterView> 切换页面时抛，就会中断 patch，
   *   表现为「点了菜单 URL 变了、页面不换，刷新才好」。
   *   一个空路径不该让整张表炸掉。
   */
  const ellipsisPath = (p?: string | null, head = 14, tail = 12) => {
    if (!p) return ''
    return p.length > head + tail + 3 ? `${p.slice(0, head)}…${p.slice(-tail)}` : p
  }

  return { rel, abs, cost, bytes, levelColor, levelBg, highlight, ellipsisPath, logTitle, dayjs }
}
