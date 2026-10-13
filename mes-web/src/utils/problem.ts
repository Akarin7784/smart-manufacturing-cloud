/**
 * RFC 9457 problem+json 错误体解析。
 * Gateway 与各服务失败时返回 application/problem+json（如 authentication-required / access-denied），
 * 这里统一转成前端可展示的结构；非 problem 响应则退化为通用提示，避免把原始报文暴露给用户。
 */
export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  [key: string]: unknown
}

export interface ParsedError {
  status: number
  message: string
  problem?: Problem
}

/** 判断 Content-Type 是否为 problem+json（忽略大小写与 charset 参数）。 */
export function isProblemJson(contentType: unknown): boolean {
  return typeof contentType === 'string' && contentType.toLowerCase().includes('application/problem+json')
}

/** 将 HTTP 状态、响应头和响应体解析为统一错误；优先使用 detail，其次 title。 */
export function parseError(status: number, contentType: unknown, body: unknown): ParsedError {
  if (body && typeof body === 'object' && (isProblemJson(contentType) || 'title' in body || 'detail' in body)) {
    const problem = body as Problem
    const message = problem.detail || problem.title || defaultMessage(status)
    return { status: problem.status ?? status, message, problem }
  }
  return { status, message: defaultMessage(status) }
}

/** 无可用错误描述时按状态码给出中文兜底提示。 */
export function defaultMessage(status: number): string {
  if (status === 0) return '网络异常，请检查与服务器的连接'
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 403) return '无权限'
  if (status === 404) return '请求的资源不存在'
  if (status >= 500) return '服务器异常，请稍后重试'
  return '请求失败'
}
