<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'

const theme = useThemeStore()
theme.init()

// 多租户：有本地 token 就把用户信息拉回来（刷新页面不丢登录态）
const auth = useAuthStore()
void auth.loadMe()
</script>

<template>
  <router-view />
</template>
