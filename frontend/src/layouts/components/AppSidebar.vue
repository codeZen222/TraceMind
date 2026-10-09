<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  Monitor, Folder, DataLine, MagicStick, Document, Setting, Fold, Expand,
} from '@element-plus/icons-vue'

const route = useRoute()
const collapsed = ref(false)

const active = computed(() => {
  const path = route.path
  if (path.startsWith('/projects/')) {
    if (path.endsWith('/monitor')) return '/monitor'
    if (path.endsWith('/logs')) return '/logs'
  }
  if (path.startsWith('/diagnosis')) return '/diagnosis'
  if (path.startsWith('/reports')) return '/reports'
  if (path.startsWith('/projects')) return '/projects'
  if (path.startsWith('/settings')) return '/settings'
  return '/workbench'
})

const menus = [
  { path: '/workbench', title: '工作台', icon: Monitor },
  { path: '/projects', title: '项目管理', icon: Folder },
  { path: '/diagnosis', title: 'Agent 诊断', icon: MagicStick },
  { path: '/reports', title: '报告中心', icon: Document },
  { path: '/settings', title: '设置', icon: Setting },
]

/** 项目详情二级菜单：进入项目路由后显示 */
const showSub = computed(() => /\/projects\/[^/]+/.test(route.path))
</script>

<template>
  <aside
    class="flex shrink-0 flex-col border-r border-border bg-surface transition-all duration-200"
    :style="{ width: collapsed ? '64px' : '220px' }"
  >
    <div class="flex h-14 items-center gap-2.5 border-b border-border px-4">
      <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="2" y="2" width="28" height="28" rx="8" fill="var(--tm-code-bg)" />
        <path d="M9 20 L14 12 L18 17 L23 9" stroke="var(--tm-accent)" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="23" cy="9" r="2.5" fill="var(--tm-accent)" />
      </svg>
      <span v-if="!collapsed" class="text-[15px] font-semibold tracking-tight text-text-1">TraceMind</span>
    </div>

    <nav class="flex-1 space-y-0.5 overflow-y-auto px-2 py-3">
      <router-link
        v-for="m in menus"
        :key="m.path"
        :to="m.path"
        class="flex items-center gap-2.5 rounded-sm px-3 py-2 text-[13.5px] text-text-2 transition-colors hover:bg-accent-soft hover:text-accent"
        :class="{ '!text-accent bg-accent-soft font-medium': active === m.path }"
        :title="collapsed ? m.title : undefined"
      >
        <el-icon :size="17"><component :is="m.icon" /></el-icon>
        <span v-if="!collapsed">{{ m.title }}</span>
      </router-link>

      <template v-if="showSub && !collapsed">
        <div class="mt-3 px-3 pb-1 text-[11px] uppercase tracking-wider text-text-3">项目</div>
        <router-link
          :to="`/projects${route.path.match(/\/projects\/([^/]+)/)?.[1] ? '/' + route.path.match(/\/projects\/([^/]+)/)![1] + '/monitor' : ''}`"
          class="flex items-center gap-2.5 rounded-sm px-3 py-1.5 pl-6 text-[13px] text-text-2 hover:text-accent"
          :class="{ '!text-accent': active === '/monitor' }"
        >
          <el-icon :size="15"><Monitor /></el-icon> 监听配置
        </router-link>
        <router-link
          :to="`/projects/${route.path.match(/\/projects\/([^/]+)/)?.[1] ?? ''}/logs`"
          class="flex items-center gap-2.5 rounded-sm px-3 py-1.5 pl-6 text-[13px] text-text-2 hover:text-accent"
          :class="{ '!text-accent': active === '/logs' }"
        >
          <el-icon :size="15"><DataLine /></el-icon> 日志检索
        </router-link>
      </template>
    </nav>

    <button
      class="flex items-center gap-2 border-t border-border px-4 py-3 text-text-3 hover:text-text-1"
      @click="collapsed = !collapsed"
    >
      <el-icon :size="16">
        <component :is="collapsed ? Expand : Fold" />
      </el-icon>
      <span v-if="!collapsed" class="text-[13px]">收起侧边栏</span>
    </button>
  </aside>
</template>
