import { defineStore } from 'pinia'

/** 登录态：访问令牌及签发时的用户快照。令牌存 sessionStorage，关闭标签页即失效，降低泄露面。 */
export interface AuthUser {
  username: string
  role: string
  permissions: string[]
}

const TOKEN_KEY = 'mes.token'
const USER_KEY = 'mes.user'
const EXPIRES_KEY = 'mes.expiresAt'

function readUser(): AuthUser | null {
  const raw = sessionStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    // 存储被篡改或损坏时视为未登录，而不是让页面崩溃
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: sessionStorage.getItem(TOKEN_KEY) || '',
    expiresAt: Number(sessionStorage.getItem(EXPIRES_KEY) || 0),
    user: readUser()
  }),
  getters: {
    /** 有令牌且未过期才算已登录；令牌过期由后端最终裁决，这里仅避免无谓请求。 */
    isAuthenticated: (s) => !!s.token && (s.expiresAt === 0 || Date.now() < s.expiresAt)
  },
  actions: {
    /** 按令牌签发时的权限快照判断是否显示操作入口；最终授权仍由 Gateway 与业务服务校验。 */
    can(permission: string): boolean {
      return !!this.user?.permissions?.includes(permission)
    },
    /** 保存 auth-service 登录响应；expiresIn 单位为秒。 */
    setSession(token: string, expiresIn: number, user: AuthUser) {
      this.token = token
      this.expiresAt = Date.now() + expiresIn * 1000
      this.user = user
      sessionStorage.setItem(TOKEN_KEY, token)
      sessionStorage.setItem(EXPIRES_KEY, String(this.expiresAt))
      sessionStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    /** 清除登录态；后端短期令牌不即时吊销，前端只负责丢弃。 */
    clear() {
      this.token = ''
      this.expiresAt = 0
      this.user = null
      sessionStorage.removeItem(TOKEN_KEY)
      sessionStorage.removeItem(EXPIRES_KEY)
      sessionStorage.removeItem(USER_KEY)
    }
  }
})
