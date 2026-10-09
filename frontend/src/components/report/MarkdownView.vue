<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const props = defineProps<{ markdown: string }>()

const md = new MarkdownIt({ html: false, linkify: true, breaks: false })

/** 渲染前 MUST 经 DOMPurify 净化（防 XSS） */
const html = computed(() => {
  const raw = md.render(props.markdown)
  return DOMPurify.sanitize(raw)
})

/** h1~h3 目录大纲 */
const outline = computed(() =>
  props.markdown
    .split('\n')
    .map((line) => /^#{1,3}\s+(.+)/.exec(line))
    .filter(Boolean)
    .map((m, i) => {
      const text = (m as RegExpExecArray)[1]
      return { id: `tm-h-${i}`, text }
    }),
)
</script>

<template>
  <div class="grid grid-cols-12 gap-4">
    <article
      class="markdown-body col-span-12 rounded-lg border border-border bg-surface p-6 lg:col-span-8"
      v-html="html"
    />
    <aside v-if="outline.length" class="hidden col-span-4 lg:block">
      <nav class="sticky top-4 rounded-lg border border-border bg-surface p-4 text-[12.5px]">
        <div class="mb-2 text-[11px] font-semibold uppercase tracking-wide text-text-3">目录</div>
        <ul class="space-y-1.5">
          <li v-for="h in outline" :key="h.id" class="truncate text-text-2 hover:text-accent">
            {{ h.text }}
          </li>
        </ul>
      </nav>
    </aside>
  </div>
</template>

<style scoped>
.markdown-body :deep(h1) { font-size: 20px; margin: 0.4em 0 0.6em; }
.markdown-body :deep(h2) { font-size: 16px; margin: 1.4em 0 0.5em; padding-bottom: 0.3em; border-bottom: 1px solid var(--tm-border); }
.markdown-body :deep(h3) { font-size: 14px; margin: 1.1em 0 0.4em; }
.markdown-body :deep(p) { margin: 0.5em 0; color: var(--tm-text-1); font-size: 13.5px; line-height: 1.7; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 1.4em; margin: 0.5em 0; font-size: 13.5px; color: var(--tm-text-1); }
.markdown-body :deep(li) { margin: 0.25em 0; }
.markdown-body :deep(code) {
  font-family: var(--tm-font-mono); font-size: 12.5px;
  background: var(--tm-code-bg); padding: 1px 5px; border-radius: 4px; color: var(--tm-accent);
}
.markdown-body :deep(pre) { background: var(--tm-code-bg); border: 1px solid var(--tm-border); border-radius: 6px; padding: 12px; overflow: auto; margin: 0.8em 0; }
.markdown-body :deep(pre code) { background: transparent; padding: 0; color: var(--tm-text-1); }
.markdown-body :deep(table) { border-collapse: collapse; margin: 0.8em 0; width: 100%; font-size: 12.5px; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid var(--tm-border); padding: 6px 10px; text-align: left; }
.markdown-body :deep(th) { background: var(--tm-bg); color: var(--tm-text-2); font-weight: 600; }
.markdown-body :deep(blockquote) { border-left: 3px solid var(--tm-accent); margin: 0.8em 0; padding: 2px 14px; color: var(--tm-text-2); background: var(--tm-accent-soft); border-radius: 0 6px 6px 0; }
.markdown-body :deep(a) { color: var(--tm-accent); }
</style>
