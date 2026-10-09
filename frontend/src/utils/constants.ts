/** localStorage 里存 JWT 的 key。request.ts 拦截器和 auth store 共用，避免循环依赖。 */
export const TOKEN_KEY = 'tm-token'
