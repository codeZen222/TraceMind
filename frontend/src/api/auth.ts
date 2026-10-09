import { get, post } from './request'
import type { AuthResponse, User } from '@/types/auth'

export const login = (data: { email: string; password: string }) =>
  post<AuthResponse>('/auth/login', data)

export const register = (data: { email: string; password: string; nickname: string }) =>
  post<AuthResponse>('/auth/register', data)

/** 当前登录用户（刷新页面时把本地 token 换回用户信息） */
export const me = () => get<User>('/auth/me')
