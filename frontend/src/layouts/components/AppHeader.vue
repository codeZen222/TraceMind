<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ThemeToggle from './ThemeToggle.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function logout() {
  auth.logout()
  void router.push('/login')
}

/**
 * 全局搜索。
 *
 * ★ 这个输入框原来是写死 disabled 的 —— 一个永远灰着、点了没反应的搜索框，
 *   比没有搜索框更让人怀疑"这系统是不是没做完"。
 *   现在的行为：
 *     · 输入会话 ID（sess-xxxx 或简写 #xxxx）→ 直接进该诊断会话
 *     · 其它内容 → 跳到项目管理并按项目名过滤
 */
const keyword = ref('')

function submitSearch() {
  const raw = keyword.value.trim()
  if (!raw) return
  const sessionId = raw.startsWith('#') ? `sess-${raw.slice(1)}` : raw
  if (/^sess-[\w-]+$/.test(sessionId)) {
    void router.push(`/diagnosis/${sessionId}`)
    return
  }
  void router.push({ name: 'projects', query: { keyword: raw } })
}

const crumbs = computed(() => {
  if (route.path.startsWith('/projects/') && route.path.endsWith('/monitor')) return ['项目管理', '监听配置']
  if (route.path.startsWith('/projects/') && route.path.endsWith('/logs')) return ['项目管理', '日志检索']
  if (route.path.startsWith('/projects')) return ['项目管理']
  if (route.path.startsWith('/diagnosis')) return ['Agent 诊断']
  if (route.path.startsWith('/reports/')) return ['报告中心', '报告详情']
  if (route.path.startsWith('/reports')) return ['报告中心']
  if (route.path.startsWith('/settings')) return ['系统设置']
  return []
})
</script>

<template>
  <header class="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-surface px-6">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item v-for="(c, i) in crumbs" :key="i">{{ c }}</el-breadcrumb-item>
    </el-breadcrumb>
    <div class="ml-auto flex items-center gap-3">
      <el-input
        v-model="keyword"
        placeholder="搜索项目名 / 会话 ID，回车"
        class="!w-56"
        size="small"
        clearable
        @keyup.enter="submitSearch"
      />
      <ThemeToggle />
      <!-- 当前用户 + 退出（多租户） -->
      <div class="flex items-center gap-2 border-l border-border pl-3">
        <span class="max-w-[120px] truncate text-[12.5px] text-text-2" :title="auth.user?.email">
          {{ auth.user?.nickname ?? auth.user?.email ?? '…' }}
        </span>
        <button
          class="rounded px-1.5 py-0.5 text-[12px] text-text-3 transition-colors hover:bg-accent-soft hover:text-accent"
          @click="logout"
        >
          退出
        </button>
      </div>
    </div>
  </header>
</template>
