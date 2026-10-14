/**
 * 派工 / 工序任务接口（PR #68 已实现，契约见 docs/workorder-dispatch-design.md 与 docs/api/workorder-openapi.yaml）。
 * 写操作经 Gateway 调用 workorder-service，必须带 Idempotency-Key；操作工下拉为 GET /api/auth/operators。
 */
import { http } from './http'
import { getProgress } from './workorders'

/** 工序任务状态；是否已派工看 stationCode 是否为空（契约 OperationTaskStatus）。 */
export type OperationTaskStatus = 'PENDING' | 'READY' | 'RUNNING' | 'PAUSED' | 'DONE' | 'CANCELLED'

/** 工序任务（含派工信息），对应契约 OperationTaskView。 */
export interface OperationTask {
  workOrderNo: string
  operationNo: string
  name: string
  workCenterCode: string
  status: OperationTaskStatus
  stationCode: string | null
  operator: string | null
  plannedStart: string | null
  plannedEnd: string | null
  dispatchedAt: string | null
  dispatchedBy: string | null
  version: number
}

/** 派工请求（DispatchRequest）。 */
export interface DispatchRequest {
  stationCode: string
  operator?: string | null
  plannedStart?: string | null
  plannedEnd?: string | null
  note?: string | null
}

/** 改派请求（ReassignRequest），expectedVersion 为读到的任务版本（乐观锁）。 */
export interface ReassignRequest extends DispatchRequest {
  expectedVersion: number
}

/**
 * 操作工（GET /api/auth/operators，OperatorView）。id 为令牌 sub，派工 operator 传该值；
 * 工号为空时后端返回 null，姓名为空时回退为用户名。
 */
export interface Operator {
  id: string
  employeeNo: string | null
  name: string
}

/**
 * 下拉显示：有工号显示「工号 姓名」；工号为 null / 空白只显示姓名。
 * 历史数据中工号可能等于姓名（旧实现回退为用户名），同样只显示一次，避免「admin admin」。
 */
export function operatorLabel(o: Operator): string {
  const no = (o.employeeNo ?? '').trim()
  return no && no !== o.name ? `${no} ${o.name}` : o.name
}

export interface OperationTaskQuery {
  station?: string
  status?: OperationTaskStatus[]
  operator?: string
  page?: number
  size?: number
}

/** 派工接口抽象：所有写操作都带 Idempotency-Key，调用方在同一次操作的重试中复用同一个键。 */
export interface DispatchApi {
  /** 工单详情中的任务列表（真实实现取自 /progress 附带的派工信息）。 */
  listTasks(workOrderNo: string): Promise<OperationTask[]>
  /** 工位工作台任务列表 GET /api/operation-tasks。 */
  queryTasks(q: OperationTaskQuery): Promise<OperationTask[]>
  dispatch(wo: string, opNo: string, req: DispatchRequest, key: string): Promise<OperationTask>
  reassign(wo: string, opNo: string, req: ReassignRequest, key: string): Promise<OperationTask>
  cancelDispatch(wo: string, opNo: string, reason: string, key: string): Promise<OperationTask>
  start(wo: string, opNo: string, key: string, reason?: string): Promise<OperationTask>
  pause(wo: string, opNo: string, reason: string, key: string): Promise<OperationTask>
  resume(wo: string, opNo: string, key: string, reason?: string): Promise<OperationTask>
  listOperators(q?: string): Promise<Operator[]>
}

/** 是否已派工。 */
export function isDispatched(t: Pick<OperationTask, 'stationCode'>): boolean {
  return !!t.stationCode
}

/** 前端可用动作，与设计稿 §3 状态机一致；最终以后端校验为准。 */
export function taskActions(t: OperationTask) {
  const d = isDispatched(t)
  return {
    dispatch: !d && (t.status === 'PENDING' || t.status === 'READY'),
    // 改派仅 READY / PAUSED（已拍板）；RUNNING 需先暂停
    reassign: d && (t.status === 'READY' || t.status === 'PAUSED'),
    cancelDispatch: d && (t.status === 'PENDING' || t.status === 'READY'),
    start: d && t.status === 'READY',
    pause: t.status === 'RUNNING',
    resume: t.status === 'PAUSED'
  }
}

/** 改派不可用时的中文原因，用于按钮旁提示。 */
export function reassignBlockedReason(t: OperationTask): string {
  if (!isDispatched(t)) return '任务尚未派工，请先派工'
  if (t.status === 'RUNNING') return '任务进行中，请先暂停再改派'
  return '仅待开工或已暂停的任务可以改派'
}

const enc = encodeURIComponent
interface Envelope<T> { data: T }
const opPath = (wo: string, op: string, action: string) => `/workorders/${enc(wo)}/operations/${enc(op)}/${action}`

/** 真实 HTTP 实现（路径、请求体与契约一致）。 */
export function createHttpDispatchApi(): DispatchApi {
  const post = async (wo: string, op: string, action: string, body: unknown, key: string) =>
    (await http.post<Envelope<OperationTask>>(opPath(wo, op, action), body, { headers: { 'Idempotency-Key': key } })).data.data
  return {
    async listTasks(wo) {
      // #70 起 /progress 直接带工序名称与工作中心，不再额外请求 /release
      const p = await getProgress(wo)
      return p.operations.map((o) => ({
        workOrderNo: wo, operationNo: o.operationNo,
        name: o.operationName ?? '', workCenterCode: o.workCenterCode ?? '',
        status: (o.taskStatus ?? 'PENDING') as OperationTaskStatus, stationCode: o.stationCode ?? null, operator: o.operator ?? null,
        plannedStart: o.plannedStart ?? null, plannedEnd: o.plannedEnd ?? null, dispatchedAt: null, dispatchedBy: null, version: o.version ?? 0
      }))
    },
    async queryTasks(q) {
      return (await http.get<Envelope<OperationTask[]>>('/operation-tasks', { params: q, paramsSerializer: { indexes: null } })).data.data
    },
    dispatch: (wo, op, req, key) => post(wo, op, 'dispatch', req, key),
    reassign: (wo, op, req, key) => post(wo, op, 'reassign', req, key),
    cancelDispatch: (wo, op, reason, key) => post(wo, op, 'cancel-dispatch', { reason }, key),
    start: (wo, op, key, reason) => post(wo, op, 'start', { reason: reason ?? null }, key),
    pause: (wo, op, reason, key) => post(wo, op, 'pause', { reason }, key),
    resume: (wo, op, key, reason) => post(wo, op, 'resume', { reason: reason ?? null }, key),
    async listOperators(q) {
      return (await http.get<Envelope<Operator[]>>('/auth/operators', { params: q ? { q } : {} })).data.data
    }
  }
}

const api = createHttpDispatchApi()

/** 派工接口实例（始终调用真实后端）。 */
export function dispatchApi(): DispatchApi {
  return api
}
