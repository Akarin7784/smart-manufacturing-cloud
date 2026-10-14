// 保护请求层：附加 Bearer 令牌、401 跳登录、403 提示无权限、登录接口 401 不跳转。
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { http, setupHttp, resetHttpForTest, HttpError } from '@/api/http'
import { login } from '@/api/auth'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { freshPinia } from './setup'

vi.mock('element-plus', async (orig) => {
  const m = await orig<typeof import('element-plus')>()
  return { ...m, ElMessage: Object.assign(vi.fn(), { warning: vi.fn(), error: vi.fn() }) }
})

type Reply = { status: number; data: unknown; headers?: Record<string, string> }
let seen: InternalAxiosRequestConfig | undefined
function adapterReturning(r: Reply) {
  http.defaults.adapter = async (config) => {
    seen = config
    const resp = { status: r.status, statusText: '', data: r.data, headers: r.headers ?? {}, config }
    if (r.status >= 400) throw new AxiosError('fail', 'ERR', config, null, resp)
    return resp
  }
}

describe('http 请求层', () => {
  let router: ReturnType<typeof createAppRouter>
  beforeEach(async () => {
    freshPinia()
    resetHttpForTest()
    vi.clearAllMocks()
    router = createAppRouter(true)
    setupHttp(router)
    setupHttp(router) // 重复安装不应叠加
    useAuthStore().setSession('tok', 900, { username: 'a', role: 'ADMIN', permissions: [] })
    await router.push('/reports')
  })

  it('附加 Bearer 令牌', async () => {
    adapterReturning({ status: 200, data: { ok: 1 } })
    await http.get('/workorders')
    expect(AxiosHeaders.from(seen!.headers).get('Authorization')).toBe('Bearer tok')
  })

  it('未登录时不附加令牌', async () => {
    useAuthStore().clear()
    adapterReturning({ status: 200, data: {} })
    await http.get('/x')
    expect(AxiosHeaders.from(seen!.headers).get('Authorization')).toBeFalsy()
  })

  it('401 清除登录态并跳转登录页', async () => {
    adapterReturning({ status: 401, data: { type: 'authentication-required', title: '需要登录', status: 401 }, headers: { 'content-type': 'application/problem+json' } })
    await expect(http.get('/workorders')).rejects.toBeInstanceOf(HttpError)
    expect(useAuthStore().token).toBe('')
    // 拦截器内的跳转不被调用方 await，且懒加载组件解析耗时随环境变化，用 waitFor 等待导航真正完成，而非固定延时
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'), { timeout: 5000 })
    expect(router.currentRoute.value.query.redirect).toBe('/reports')
    expect(ElMessage.warning).toHaveBeenCalledWith('需要登录')
  })

  it('已在登录页时 401 不重复跳转', async () => {
    useAuthStore().clear()
    await router.push('/login')
    adapterReturning({ status: 401, data: {} })
    await expect(http.get('/workorders')).rejects.toMatchObject({ status: 401 })
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })

  it('403 提示无权限', async () => {
    adapterReturning({ status: 403, data: { type: 'access-denied', title: 'Forbidden', status: 403 }, headers: { 'content-type': 'application/problem+json' } })
    await expect(http.post('/workorders', {})).rejects.toMatchObject({ status: 403 })
    expect(ElMessage.error).toHaveBeenCalledWith('无权限')
  })

  it('网络错误给出兜底提示', async () => {
    http.defaults.adapter = async (config) => { throw new AxiosError('net', 'ERR_NETWORK', config) }
    await expect(http.get('/x')).rejects.toMatchObject({ status: 0 })
  })

  it('登录成功解包 ApiResponse', async () => {
    adapterReturning({ status: 200, data: { code: 'OK', message: 'success', data: { accessToken: 'jwt', expiresIn: 900 } } })
    expect((await login({ username: 'u', password: 'p' })).accessToken).toBe('jwt')
    expect(seen!.url).toBe('/auth/login')
  })

  it('登录接口 401 不跳转，交给登录页展示', async () => {
    adapterReturning({ status: 401, data: { title: '用户名或密码错误', status: 401 }, headers: { 'content-type': 'application/problem+json' } })
    await expect(login({ username: 'u', password: 'x' })).rejects.toThrow('用户名或密码错误')
    expect(router.currentRoute.value.name).toBe('reports')
  })
})
