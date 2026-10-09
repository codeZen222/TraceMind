/** 用户信息（与后端 UserVO 对齐）。⚠️ 不含密码。 */
export interface User {
  id: string
  email: string
  nickname: string
}

/** 登录 / 注册成功后返回：JWT + 用户信息 */
export interface AuthResponse {
  token: string
  user: User
}
