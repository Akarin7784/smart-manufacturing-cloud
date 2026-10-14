import axios, { AxiosError, type AxiosInstance } from 'axios'
import type { Router } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { parseError, type ParsedError } from '@/utils/problem'

/**
 * 统一 HTTP 客户端：baseURL 为 /api，开发时由 Vite 代理、部署时由反向代理转发到 Gateway。
 * 请求拦截器附加 Bearer 令牌；响应拦截器解析 problem+json，401 回登录页，403 提示无权限。
 */
export const http: AxiosInstance = axios.create({ baseURL: '/api', timeout: 15000 })

/** 被拦截器拒绝时抛出的错误，携带解析后的状态码和提示语。 */
export class HttpError extends Error {
  readonly status: number
  readonly parsed: ParsedError
  constructor(parsed: ParsedError) {
    super(parsed.message)
    this.status = parsed.status
    this.parsed = parsed
  }
}

let installed = false

/** 安装拦截器；重复调用只生效一次，避免测试或热更新时叠加拦截器。 */
export function setupHttp(router: Router) {
  if (installed) return
  installed = true
  http.interceptors.request.use((config) => {
    const auth = useAuthStore()
    if (auth.token) config.headers.set('Authorization', `Bearer ${auth.token}`)
    return config
  })
  http.interceptors.response.use(
    (resp) => resp,
    (err: AxiosError) => Promise.reject(handleError(err, router))
  )
}

/** 将 axios 错误转换为 HttpError，并执行 401/403 的界面副作用。 */
export function handleError(err: AxiosError, router: Router): HttpError {
  const resp = err.response
  const parsed = parseError(resp?.status ?? 0, resp?.headers?.['content-type'], resp?.data)
  if (parsed.status === 401) {
    // 登录接口本身返回 401 表示账号或密码错误，交给登录页展示，不做跳转
    const isLogin = err.config?.url?.includes('/auth/login')
    if (!isLogin) {
      useAuthStore().clear()
      const current = router.currentRoute.value
      if (current.name !== 'login') {
        router.replace({ name: 'login', query: { redirect: current.fullPath } })
      }
      ElMessage.warning(parsed.message)
    }
  } else if (parsed.status === 403) {
    ElMessage.error('无权限')
  }
  return new HttpError(parsed)
}

/** 仅供测试重置安装状态。 */
export function resetHttpForTest() {
  installed = false
  http.interceptors.request.clear()
  http.interceptors.response.clear()
}
