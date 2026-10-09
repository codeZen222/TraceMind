/**
 * 项目分类色 —— 同一项目在全站（会话列表、诊断面板、项目列表）颜色一致。
 * 用 projectId 做稳定哈希映射到 6 色令牌，避免每次渲染跳色。
 */
const CAT_COUNT = 6

function hash(str: string): number {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = (h * 31 + str.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

/** 返回 CSS 变量引用，如 'var(--tm-cat-3)'，随主题自动切换明暗变体 */
export function projectColor(projectId: string | undefined | null): string {
  if (!projectId) return 'var(--tm-text-3)'
  return `var(--tm-cat-${(hash(projectId) % CAT_COUNT) + 1})`
}
