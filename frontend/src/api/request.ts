import axios from 'axios'
import { mockRequest } from '@/mock/handlers'
import { TOKEN_KEY } from '@/utils/constants'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const API_BASE = import.meta.env.VITE_API_BASE ?? '/api'

const http = axios.create({
  baseURL: API_BASE,
  timeout: 15_000,
})

// 请求拦截：带上 JWT（多租户，每个接口都要鉴权）
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => res,
  (err) => {
    // 登录过期 / 未认证：清 token，跳登录页（认证失败后端返回真实 401）
    if (err?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    return Promise.reject(new Error(err?.response?.data?.message ?? '网络请求失败'))
  },
)

export interface RequestOptions {
  data?: unknown
  params?: Record<string, unknown>
}

/**
 * 统一请求入口：
 * - VITE_USE_MOCK=true → 走 mock/handlers.ts，不发真实 HTTP
 * - 否则 → Axios，统一解包 ApiResponse
 */
export async function request<T>(method: string, url: string, opts: RequestOptions = {}): Promise<T> {
  const fullUrl = url.startsWith('/api') ? url : `${API_BASE}${url}`
  if (USE_MOCK) {
    return mockRequest<T>(method, fullUrl, opts.data, opts.params)
  }
  const res = await http.request<{ code: number; message: string; data: T }>({
    method: method as never,
    url: fullUrl.replace(API_BASE, ''),
    data: opts.data,
    params: opts.params,
  })
  const body = res.data
  if (body.code !== 0) throw new Error(body.message || '业务错误')
  return body.data
}

export const get = <T>(url: string, params?: Record<string, unknown>) =>
  request<T>('GET', url, { params })
export const post = <T>(url: string, data?: unknown) =>
  request<T>('POST', url, { data })
export const put = <T>(url: string, data?: unknown) =>
  request<T>('PUT', url, { data })
export const del = <T>(url: string) => request<T>('DELETE', url)
