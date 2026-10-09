<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const nickname = ref('')
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

async function submit() {
  error.value = ''
  if (!nickname.value.trim()) {
    error.value = '请输入昵称'
    return
  }
  if (!EMAIL_RE.test(email.value.trim())) {
    error.value = '邮箱格式不正确'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码至少 6 位'
    return
  }
  loading.value = true
  try {
    await auth.register(email.value.trim(), password.value, nickname.value.trim())
    await router.replace('/workbench')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '注册失败'
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
          <h1 class="mt-10 text-[24px] font-semibold leading-snug">创建你的账号</h1>
          <p class="mt-3 text-[13px] leading-relaxed text-white/80">
            每个账号的数据相互隔离。<br />项目、日志、诊断只对你自己可见。
          </p>
        </div>
        <p class="text-[12px] text-white/60">私有部署 · 数据只属于你的账号</p>
      </div>

      <!-- 右侧表单区 -->
      <div class="p-8 sm:p-10">
        <div class="lg:hidden">
          <span class="text-[16px] font-semibold text-[#1a1d21]">TraceMind</span>
        </div>
        <h2 class="mt-1 text-[20px] font-semibold text-[#1a1d21] lg:mt-6">注册</h2>
        <p class="mt-1 text-[13px] text-[#98a2b3]">创建账号后即可接入你的日志</p>

        <form class="mt-7 space-y-4" @submit.prevent="submit">
          <div>
            <label class="mb-1.5 block text-[13px] font-medium text-[#1a1d21]">昵称</label>
            <input
              v-model="nickname"
              type="text"
              autocomplete="nickname"
              placeholder="怎么称呼你"
              class="h-10 w-full rounded-md border border-[#d0d5dd] bg-white px-3 text-[14px] text-[#1a1d21] outline-none transition-colors placeholder:text-[#98a2b3] focus:border-[#0f8a7e]"
            />
          </div>
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
              autocomplete="new-password"
              placeholder="至少 6 位"
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
            {{ loading ? '注册中…' : '注册' }}
          </button>
        </form>

        <p class="mt-6 text-center text-[13px] text-[#5c6470]">
          已有账号？
          <router-link to="/login" class="font-medium text-[#0f8a7e] hover:underline">登录</router-link>
        </p>
      </div>
    </div>
  </div>
</template>
