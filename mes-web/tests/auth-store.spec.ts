// 保护登录态的保存、过期判断、清除及损坏存储容错。
import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { freshPinia } from './setup'

describe('auth store', () => {
  beforeEach(() => freshPinia())
  it('保存与清除会话', () => {
    const s = useAuthStore()
    expect(s.isAuthenticated).toBe(false)
    s.setSession('tk', 900, { username: 'admin', role: 'ADMIN', permissions: [] })
    expect(s.isAuthenticated).toBe(true)
    expect(sessionStorage.getItem('mes.token')).toBe('tk')
    s.clear()
    expect(s.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem('mes.user')).toBeNull()
  })
  it('过期令牌视为未登录', () => {
    const s = useAuthStore()
    s.setSession('tk', -1, { username: 'a', role: 'VIEWER', permissions: [] })
    expect(s.isAuthenticated).toBe(false)
  })
  it('从存储恢复，损坏的用户数据视为空', () => {
    sessionStorage.setItem('mes.user', '{bad')
    sessionStorage.setItem('mes.token', 't')
    expect(useAuthStore().user).toBeNull()
    freshPinia()
    sessionStorage.setItem('mes.user', JSON.stringify({ username: 'u', role: 'R', permissions: [] }))
    expect(useAuthStore().user?.username).toBe('u')
  })
})
