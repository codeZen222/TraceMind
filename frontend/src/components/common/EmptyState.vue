<script setup lang="ts">
import { FolderOpened } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    text?: string
    actionText?: string
  }>(),
  { text: '暂无数据', actionText: '' },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center gap-3 py-12 text-center">
    <svg width="42" height="42" viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <rect x="8" y="14" width="32" height="24" rx="4" stroke="var(--tm-border-strong)" stroke-width="2" />
      <path d="M8 22h32" stroke="var(--tm-border-strong)" stroke-width="2" />
      <path d="M17 14l3-5h8l3 5" stroke="var(--tm-border-strong)" stroke-width="2" />
      <circle cx="24" cy="30" r="3.5" stroke="var(--tm-accent)" stroke-width="2" />
    </svg>
    <p class="text-[13.5px] text-text-3">{{ text }}</p>
    <el-button v-if="actionText" type="primary" size="small" plain @click="emit('action')">
      <el-icon class="mr-1"><FolderOpened /></el-icon>{{ actionText }}
    </el-button>
  </div>
</template>
