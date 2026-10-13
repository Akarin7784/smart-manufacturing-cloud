// 保护路由守卫：未登录重定向到登录页，已登录访问登录页回首页。
import { describe, it, expect, beforeEach } from 'vitest'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { freshPinia } from './setup'

describe('路由守卫', () => {
  beforeEach(() => freshPinia())
  it('未登录访问受保护页跳登录并带回跳地址', async () => {
    const r = createAppRouter(true)
    await r.push('/reports')
    expect(r.currentRoute.value.name).toBe('login')
    expect(r.currentRoute.value.query.redirect).toBe('/reports')
  })
  it('已登录访问登录页回首页工单', async () => {
    useAuthStore().setSession('t', 900, { username: 'a', role: 'ADMIN', permissions: [] })
    const r = createAppRouter(true)
    await r.push('/login')
    expect(r.currentRoute.value.name).toBe('workorders')
    await r.push('/unknown')
    expect(r.currentRoute.value.name).toBe('workorders')
  })
})
