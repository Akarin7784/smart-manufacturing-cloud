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

/**
 * 按 problem type 的最后一段判断错误类别（type 为相对 URI，如 `masterdata-in-use`，兼容带前缀的绝对 URI）。
 * 主数据被引用（#71 起为 problem+json 顶层 type，不再读 data.type）：`isProblemType(e.parsed.problem, 'masterdata-in-use')`。
 */
export function isProblemType(problem: Problem | undefined, type: string): boolean {
  return typeof problem?.type === 'string' && problem.type.split('/').pop() === type
}

/** 主数据被引用明细（#71 InUseData）：referenceType 为引用方类型，references 为后端返回的前若干个引用方。 */
export interface InUseDetail {
  referenceType: string | null
  references: string[]
}

/** 后端单次最多返回的引用方数量（契约 InUseData.references：前 10 个）。 */
export const IN_USE_REFERENCE_LIMIT = 10

/**
 * 解析 masterdata-in-use 问题的 data；不是该类型返回 null。
 * data 缺失或格式不对时降级：referenceType 为 null、references 为空数组；非字符串/空白的引用项被忽略。
 */
export function parseInUse(problem: Problem | undefined): InUseDetail | null {
  if (!isProblemType(problem, 'masterdata-in-use')) return null
  const data = problem?.data
  const d = data && typeof data === 'object' && !Array.isArray(data) ? (data as Record<string, unknown>) : {}
  const rt = typeof d.referenceType === 'string' && d.referenceType.trim() ? d.referenceType.trim() : null
  const refs = Array.isArray(d.references)
    ? d.references.filter((r): r is string => typeof r === 'string' && r.trim() !== '').map((r) => r.trim())
    : []
  return { referenceType: rt, references: refs }
}

const REFERENCE_TYPE_LABELS: Record<string, string> = { WORK_ORDER: '工单', BOM: 'BOM', ROUTING: '工艺路线' }

/**
 * 引用明细的中文描述，如「被工单引用：WO1、WO2、WO3 等 7 个」；无引用清单时只给类型，类型也未知时返回空串。
 * 最多列出 max 个；后端返回满 10 个时可能还有更多，表述为「至少 10 个」。
 */
export function formatInUse(d: InUseDetail, max = 3): string {
  const who = d.referenceType ? REFERENCE_TYPE_LABELS[d.referenceType] ?? d.referenceType : ''
  const prefix = who ? `被${who}引用` : '被引用'
  if (!d.references.length) return who ? prefix : ''
  const n = d.references.length
  if (n <= max) return `${prefix}：${d.references.join('、')}`
  const count = n >= IN_USE_REFERENCE_LIMIT ? `至少 ${n} 个` : `${n} 个`
  return `${prefix}：${d.references.slice(0, max).join('、')} 等${count}`
}
