// 保护登录页与主布局的交互：登录成功回跳、失败展示错误、开放重定向拦截、侧栏折叠与退出。
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

// 页面加载时会请求工单列表，这里替换为空列表，避免真实网络请求
vi.mock('@/api/workorders', async (orig) => ({ ...(await orig<typeof import('@/api/workorders')>()), listWorkOrders: vi.fn().mockResolvedValue([]) }))
import { plugins, freshPinia } from './setup'

async function mountAt(path: string) {
  const pinia = freshPinia()
  const router = createAppRouter(true)
  return { pinia, router, prepare: async () => {
    await router.push(path)
    await router.isReady()
    const w = mount(App, { global: { plugins: [...plugins, pinia, router] }, attachTo: document.body })
    // 路由组件为懒加载，等页面根节点真正渲染后再交互
    await vi.waitFor(() => expect(w.find('.login-page, .layout').exists()).toBe(true), { timeout: 5000 })
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
    // 等待登录请求与回跳导航完成（覆盖率插桩下耗时不固定，禁止用固定延时）
    await vi.waitFor(() => expect(ctx.router.currentRoute.value.path).toBe('/reports'), { timeout: 5000 })
    expect(useAuthStore().token).toBe('jwt')
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
    await vi.waitFor(() => expect(w.text()).toContain('用户名或密码错误'), { timeout: 5000 })
    spy.mockRejectedValue('x')
    await w.find('form').trigger('submit')
    await vi.waitFor(() => expect(w.text()).toContain('登录失败'), { timeout: 5000 })
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
    await vi.waitFor(() => expect(ctx.router.currentRoute.value.path).toBe('/workorders'), { timeout: 5000 })
    w.unmount()
  })
})

describe('主布局', () => {
  // 每个用例恢复默认视口宽度，避免 innerWidth 在打乱顺序时泄漏到其他用例
  beforeEach(() => Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true, writable: true }))
  afterEach(() => { document.body.innerHTML = '' })
  it('显示用户、窄屏折叠、点击切换与退出', async () => {
    const ctx = await mountAt('/reports')
    useAuthStore().setSession('t', 900, { username: 'op1', role: 'OPERATOR', permissions: [] })
    Object.defineProperty(window, 'innerWidth', { value: 800, configurable: true })
    const w = await ctx.prepare()
    expect(w.text()).toContain('op1')
    await vi.waitFor(() => expect(w.text()).toContain('选择工单'), { timeout: 5000 })
    expect(w.find('.logo').text()).toBe('MES')
    await w.find('[aria-label="切换菜单"]').trigger('click')
    expect(w.find('.logo').text()).toBe('智能制造系统')
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    window.dispatchEvent(new Event('resize'))
    await ctx.router.push('/workorders')
    await vi.waitFor(() => expect(w.text()).toContain('暂无工单'), { timeout: 5000 })
    await w.find('.logout').trigger('click')
    expect(useAuthStore().token).toBe('')
    await vi.waitFor(() => expect(ctx.router.currentRoute.value.name).toBe('login'), { timeout: 5000 })
    w.unmount()
  })
})
