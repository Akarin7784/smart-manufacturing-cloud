// 保护登录页与主布局的交互：登录成功回跳、失败展示错误、开放重定向拦截、侧栏折叠与退出。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'
import { plugins, freshPinia } from './setup'

async function mountAt(path: string) {
  const pinia = freshPinia()
  const router = createAppRouter(true)
  return { pinia, router, prepare: async () => {
    await router.push(path)
    await router.isReady()
    const w = mount(App, { global: { plugins: [...plugins, pinia, router] }, attachTo: document.body })
    await flushPromises()
    return w
  } }
}

describe('登录页', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('登录成功保存令牌并回跳', async () => {
    const ctx = await mountAt('/reports')
    const w = await ctx.prepare()
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'jwt', tokenType: 'Bearer', expiresIn: 900, username: 'admin', role: 'ADMIN', permissions: [] })
    const inputs = w.findAll('input')
    await inputs[0].setValue('admin')
    await inputs[1].setValue('pw')
    await w.find('form').trigger('submit')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 10))
    await flushPromises()
    expect(useAuthStore().token).toBe('jwt')
    expect(ctx.router.currentRoute.value.path).toBe('/reports')
    w.unmount()
  })

  it('登录失败展示错误；空表单不提交', async () => {
    const ctx = await mountAt('/login?redirect=//evil.com')
    const w = await ctx.prepare()
    const spy = vi.spyOn(authApi, 'login').mockRejectedValue(new Error('用户名或密码错误'))
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(spy).not.toHaveBeenCalled()
    const inputs = w.findAll('input')
    await inputs[0].setValue('a')
    await inputs[1].setValue('b')
    await w.find('form').trigger('submit')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 10))
    expect(w.text()).toContain('用户名或密码错误')
    spy.mockRejectedValue('x')
    await w.find('form').trigger('submit')
    await new Promise((r) => setTimeout(r, 10))
    await flushPromises()
    expect(w.text()).toContain('登录失败')
    w.unmount()
  })

  it('redirect 为外部地址时回首页', async () => {
    const ctx = await mountAt('/login?redirect=//evil.com')
    const w = await ctx.prepare()
    vi.spyOn(authApi, 'login').mockResolvedValue({ accessToken: 'j', tokenType: 'Bearer', expiresIn: 900, username: 'u', role: 'R', permissions: [] })
    const inputs = w.findAll('input')
    await inputs[0].setValue('a')
    await inputs[1].setValue('b')
    await w.find('form').trigger('submit')
    await new Promise((r) => setTimeout(r, 10))
    await flushPromises()
    expect(ctx.router.currentRoute.value.path).toBe('/workorders')
    w.unmount()
  })
})

describe('主布局', () => {
  it('显示用户、窄屏折叠、点击切换与退出', async () => {
    const ctx = await mountAt('/reports')
    useAuthStore().setSession('t', 900, { username: 'op1', role: 'OPERATOR', permissions: [] })
    Object.defineProperty(window, 'innerWidth', { value: 800, configurable: true })
    const w = await ctx.prepare()
    expect(w.text()).toContain('op1')
    expect(w.text()).toContain('报工功能开发中')
    expect(w.find('.logo').text()).toBe('MES')
    await w.find('[aria-label="切换菜单"]').trigger('click')
    expect(w.find('.logo').text()).toBe('智能制造系统')
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    window.dispatchEvent(new Event('resize'))
    await ctx.router.push('/workorders')
    await flushPromises()
    expect(w.text()).toContain('工单功能开发中')
    await w.find('.logout').trigger('click')
    await flushPromises()
    expect(useAuthStore().token).toBe('')
    expect(ctx.router.currentRoute.value.name).toBe('login')
    w.unmount()
  })
})
