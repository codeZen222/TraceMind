import { computed, ref, type ComputedRef } from 'vue'

/**
 * ECharts 主题色。
 *
 * ★ 为什么不能直接在 option 里写 `color: 'var(--tm-accent)'`
 *
 *   ECharts 默认把图形画在 <canvas> 上，而 canvas 的绘图 API **不认识 CSS 变量**——
 *   它只接受真实的颜色字符串。传 `var(--x)` 进去不会报错，只是解析失败后
 *   悄悄回落到默认色，表现出来就是：**级别分布饼图是纯黑的、柱状图是纯黑的**，
 *   深色/浅色主题切换也不会跟着变。
 *
 *   所以这里统一用 getComputedStyle 把变量读成真实色值再喂给 ECharts。
 */

/** 变量名 → 读不到时的兜底值（与 styles/tokens.css 的浅色主题保持一致） */
const FALLBACK = {
  text3: '#98a2b3',
  border: '#e4e7ec',
  accent: '#0f8a7e',
  accentSoft: 'rgba(15, 138, 126, 0.10)',
  stage2: '#0f8a7e',
  stage3: '#d9a441',
  levelError: '#e5534b',
  levelWarn: '#d9a441',
  levelInfo: '#4a8fd6',
  levelDebug: '#8b949e',
  levelTrace: '#9aa3af',
} as const

function readVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/**
 * 主题切换是通过给 <html> 加/去 `dark` 类实现的，
 * 监听这个 class 变化即可让所有图表跟着换色，不必依赖 Pinia store。
 */
const version = ref(0)
let observing = false

function startObserving() {
  if (observing || typeof MutationObserver === 'undefined') return
  observing = true
  new MutationObserver(() => {
    version.value += 1
  }).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
}

export interface ChartPalette {
  text3: string
  border: string
  accent: string
  accentSoft: string
  stage2: string
  stage3: string
  level: Record<string, string>
}

export function useChartTheme(): { palette: ComputedRef<ChartPalette> } {
  startObserving()

  const palette = computed<ChartPalette>(() => {
    // 读一下 version，让主题变化时重算
    void version.value
    return {
      text3: readVar('--tm-text-3', FALLBACK.text3),
      border: readVar('--tm-border', FALLBACK.border),
      accent: readVar('--tm-accent', FALLBACK.accent),
      accentSoft: readVar('--tm-accent-soft', FALLBACK.accentSoft),
      stage2: readVar('--tm-stage-2', FALLBACK.stage2),
      stage3: readVar('--tm-stage-3', FALLBACK.stage3),
      level: {
        ERROR: readVar('--tm-level-error', FALLBACK.levelError),
        WARN: readVar('--tm-level-warn', FALLBACK.levelWarn),
        INFO: readVar('--tm-level-info', FALLBACK.levelInfo),
        DEBUG: readVar('--tm-level-debug', FALLBACK.levelDebug),
        TRACE: readVar('--tm-level-trace', FALLBACK.levelTrace),
      },
    }
  })

  return { palette }
}
