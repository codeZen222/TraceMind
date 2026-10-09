<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!email.value.trim() || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(email.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/workbench'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-[#f6f7f9] px-4">
    <div class="grid w-full max-w-[880px] overflow-hidden rounded-xl border border-[#e4e7ec] bg-white shadow-sm lg:grid-cols-[0.9fr_1.1fr]">
      <!-- 左侧品牌区 -->
      <div class="hidden flex-col justify-between bg-[#0f8a7e] p-10 text-white lg:flex">
        <div>
          <div class="flex items-center gap-2.5">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 17l6-6-6-6" />
              <path d="M12 19h8" />
            </svg>
            <span class="text-[17px] font-semibold tracking-tight">TraceMind</span>
          </div>
          <h1 class="mt-10 text-[24px] font-semibold leading-snug">日志采集与<br />AI 根因诊断</h1>
          <ul class="mt-8 space-y-3 text-[13px] leading-relaxed text-white/80">
            <li>· 上传或监听日志，自动聚类模板</li>
            <li>· 规则命中秒级拦截，零 token 成本</li>
            <li>· 不命中规则才进 Agent 深度分析</li>
          </ul>
        </div>
        <p class="text-[12px] text-white/60">私有部署 · 数据只属于你的账号</p>
      </div>

      <!-- 右侧表单区 -->
      <div class="p-8 sm:p-10">
        <div class="lg:hidden">
          <span class="text-[16px] font-semibold text-[#1a1d21]">TraceMind</span>
        </div>
        <h2 class="mt-1 text-[20px] font-semibold text-[#1a1d21] lg:mt-6">登录</h2>
        <p class="mt-1 text-[13px] text-[#98a2b3]">使用你的邮箱登录 TraceMind</p>

        <form class="mt-7 space-y-4" @submit.prevent="submit">
          <div>
            <label class="mb-1.5 block text-[13px] font-medium text-[#1a1d21]">邮箱</label>
            <input
              v-model="email"
              type="email"
              autocomplete="email"
              placeholder="you@example.com"
              class="h-10 w-full rounded-md border border-[#d0d5dd] bg-white px-3 text-[14px] text-[#1a1d21] outline-none transition-colors placeholder:text-[#98a2b3] focus:border-[#0f8a7e]"
            />
          </div>
          <div>
            <label class="mb-1.5 block text-[13px] font-medium text-[#1a1d21]">密码</label>
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="输入密码"
              class="h-10 w-full rounded-md border border-[#d0d5dd] bg-white px-3 text-[14px] text-[#1a1d21] outline-none transition-colors placeholder:text-[#98a2b3] focus:border-[#0f8a7e]"
              @keyup.enter="submit"
            />
          </div>

          <div v-if="error" class="rounded-md border border-[#f3c6c3] bg-[#fdf1f0] px-3 py-2 text-[12.5px] text-[#c0392b]">
            {{ error }}
          </div>

          <button
            type="submit"
            :disabled="loading"
            class="h-10 w-full rounded-md bg-[#0f8a7e] text-[14px] font-medium text-white transition-colors hover:bg-[#0d786d] disabled:cursor-not-allowed disabled:opacity-60"
          >
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>

        <p class="mt-6 text-center text-[13px] text-[#5c6470]">
          还没有账号？
          <router-link to="/register" class="font-medium text-[#0f8a7e] hover:underline">注册</router-link>
        </p>
      </div>
    </div>
  </div>
</template>
