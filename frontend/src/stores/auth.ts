import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as apiLogin, register as apiRegister, me as apiMe } from '@/api/auth'
import type { User } from '@/types/auth'
import { TOKEN_KEY } from '@/utils/constants'

/**
 * 认证状态。token 持久化到 localStorage，刷新不丢登录态。
 * 用户信息不持久化 —— 刷新时用 /auth/me 重新拉，保证和后端一致。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const user = ref<User | null>(null)

  function setSession(t: string, u: User) {
    token.value = t
    user.value = u
    localStorage.setItem(TOKEN_KEY, t)
  }

  async function login(email: string, password: string) {
    const res = await apiLogin({ email, password })
    setSession(res.token, res.user)
  }

  async function register(email: string, password: string, nickname: string) {
    const res = await apiRegister({ email, password, nickname })
    setSession(res.token, res.user)
  }

  /** 刷新页面后，把 token 换回用户信息。失效就登出。 */
  async function loadMe() {
    if (!token.value) return
    try {
      user.value = await apiMe()
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, login, register, loadMe, logout }
})
