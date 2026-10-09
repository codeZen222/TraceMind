<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
</script>

<template>
  <button
    class="flex h-8 w-8 items-center justify-center rounded-sm border border-border text-text-2 transition-colors hover:border-accent hover:text-accent"
    :title="theme.current === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
    :aria-label="theme.current === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
    @click="theme.toggle()"
  >
    <svg v-if="theme.current === 'dark'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
    <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2m0 16v2M4.93 4.93l1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  </button>
</template>
