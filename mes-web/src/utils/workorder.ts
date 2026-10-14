/** 工单相关的展示与校验工具：状态中文名、可执行动作、数量格式、批次号规范化。 */
import type { WorkOrderStatus } from '@/api/workorders'

export const STATUS_LABELS: Record<WorkOrderStatus, string> = {
  CREATED: '已创建',
  RELEASED: '已下达',
  IN_PROGRESS: '生产中',
  ON_HOLD: '已冻结',
  COMPLETED: '已完工',
  CLOSED: '已关闭',
  CANCELLED: '已取消'
}

const STATUS_TAGS: Record<string, 'info' | 'primary' | 'success' | 'warning' | 'danger'> = {
  CREATED: 'info', RELEASED: 'primary', IN_PROGRESS: 'primary', ON_HOLD: 'warning', COMPLETED: 'success', CLOSED: 'info', CANCELLED: 'danger'
}

/** 未知状态原样显示，避免后端新增状态时界面空白。 */
export function statusLabel(s: string): string {
  return STATUS_LABELS[s as WorkOrderStatus] ?? s
}

export function statusTag(s: string) {
  return STATUS_TAGS[s] ?? 'info'
}

export const HOLD_REASON_LABELS: Record<string, string> = {
  MANUAL: '人工冻结', LOT_HELD: '批次冻结', FAI_FAILED: '首件不合格', EQUIPMENT_FAULT: '设备故障', MATERIAL_RESERVATION_EXPIRED: '物料预留过期'
}

/** 可报工状态：已下达或生产中。 */
export function canReport(s: string): boolean {
  return s === 'RELEASED' || s === 'IN_PROGRESS'
}

/** 前端只显示当前状态允许的动作，最终是否可执行仍以后端状态机为准。 */
export function allowedActions(s: string) {
  return {
    release: s === 'CREATED',
    hold: s === 'RELEASED' || s === 'IN_PROGRESS',
    resume: s === 'ON_HOLD',
    complete: s === 'IN_PROGRESS',
    // #70：撤回下达仅 RELEASED（无报工、无开工任务由后端校验）；关闭仅 COMPLETED
    withdraw: s === 'RELEASED',
    close: s === 'COMPLETED'
  }
}

/** 与后端 @DecimalMin(0) @Digits(integer=12, fraction=6) 对齐的非负十进制校验。 */
export function isValidQty(v: string): boolean {
  return /^\d{1,12}(\.\d{1,6})?$/.test(v.trim())
}

/** 批次号去首尾空白，空串转为 null，交由后端回退为工单号（与 ReportOperationRequest 构造器行为一致）。 */
export function normalizeLotNo(v: string | null | undefined): string | null {
  const t = (v ?? '').trim()
  return t === '' ? null : t
}

/** 去掉十进制字符串尾部多余的 0，便于车间阅读（"12.500000" → "12.5"）。 */
export function fmtQty(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '-'
  const s = String(v)
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s
}

/** 进度百分比（合格数 / 计划数），计划数非正时为 0，上限 100。 */
export function percent(good: string, planned: string): number {
  const p = Number(planned)
  if (!(p > 0)) return 0
  return Math.min(100, Math.round((Number(good) / p) * 100))
}

/** 已知 problem type 的中文说明，未知类型使用后端 detail。 */
const PROBLEM_HINTS: Record<string, string> = {
  'over-report': '报工数量超过本工序可报上限',
  'uom-mismatch': '计量单位与本工序已有报工不一致',
  'workorder-closed': '工单已关闭，不能再操作',
  'workorder-state-conflict': '工单当前状态不允许此操作',
  'operations-unreported': '仍有工序未报工，不能完工',
  'idempotency-key-conflict': '重复提交的内容与首次不一致，请刷新后重试',
  'validation-error': '提交的数据不符合要求，请检查标红字段',
  'task-state-conflict': '派工任务当前状态不允许此操作',
  'masterdata-in-use': '该主数据正在被引用，不能删除或停用',
  'task-not-dispatched': '工序尚未派工，请先派工再开工',
  'station-not-found': '工位不存在、已删除或不属于该工序的工作中心',
  'concurrent-modification': '数据已被他人修改，请刷新后重试',
  'workorder-cancelled': '工单已取消，不能再操作',
  'open-ncr-exists': '该工单仍有未结案的不合格品（NCR），请先在质量模块处理后再关闭',
  'quality-service-unavailable': '质量服务暂不可用，无法确认不合格品是否结案，暂不能关闭工单，请稍后重试'
}

/** 根据 problem type 的最后一段匹配中文说明。 */
export function problemHint(type: unknown): string | undefined {
  if (typeof type !== 'string') return undefined
  const key = type.split('/').pop() as string
  return PROBLEM_HINTS[key]
}

/**
 * 解析 validation-error 的 detail（后端格式为 "field: 消息; field2: 消息"，按字段排序拼接）为字段→消息映射，
 * 用于把错误回显到对应表单项；无法识别的片段忽略。
 */
export function parseFieldErrors(detail: unknown): Record<string, string> {
  const out: Record<string, string> = {}
  if (typeof detail !== 'string') return out
  for (const part of detail.split(';')) {
    const m = part.match(/^\s*([A-Za-z_][\w.]*)\s*:\s*(.+?)\s*$/)
    if (m) out[m[1]] = out[m[1]] ? `${out[m[1]]}；${m[2]}` : m[2]
  }
  return out
}

/** 工时校验：非负，最多 2 位小数（单位：小时）。 */
export function isValidHours(v: string): boolean {
  return /^\d{1,6}(\.\d{1,2})?$/.test(v.trim())
}
