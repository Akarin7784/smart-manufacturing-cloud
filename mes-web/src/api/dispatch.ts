/**
 * 派工 / 工序任务接口，按契约草案 feat/m1-workorder-dispatch（73e5629）的
 * docs/workorder-dispatch-design.md 与 docs/api/workorder-openapi.yaml 定义。
 *
 * 【MOCK】后端实现（PR1）尚未合入，默认使用内存模拟实现 createMockDispatchApi，不发网络请求。
 * 后端上线后设置 VITE_DISPATCH_MOCK=false 即切换到 createHttpDispatchApi，页面无需改动。
 * 操作工下拉 GET /api/auth/operators 同样尚未实现，一并模拟。
 */
import { http, HttpError } from './http'
import { getProgress, getRelease } from './workorders'

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
 * 操作工（GET /api/auth/operators，PR #68 OperatorView）。id 为令牌 sub，派工 operator 传该值；
 * 后端在工号 / 姓名为空时回退为用户名，前端仍按可空处理以防历史数据。
 */
export interface Operator {
  id: string
  employeeNo: string | null
  name: string
}

/**
 * 下拉显示：有工号显示「工号 姓名」，工号为空只显示姓名。
 * PR #68 在工号为空时把 employeeNo 回退成用户名，此时常与 name 相同（如 "admin"），也只显示姓名，避免「admin admin」。
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
      // /progress 带派工信息但不含工序名称与工作中心（PR #68 的 OperationProgressView），
      // 这两项从下达快照 /release 补齐；快照不可用时降级为空，工位下拉不过滤
      const [p, rel] = await Promise.all([getProgress(wo), getRelease(wo).catch(() => null)])
      const ops = new Map((rel?.operations ?? []).map((o) => [o.opNo, o]))
      return p.operations.map((o) => ({
        workOrderNo: wo, operationNo: o.operationNo,
        name: ops.get(o.operationNo)?.name ?? '', workCenterCode: ops.get(o.operationNo)?.workCenterCode ?? '',
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

/** 模拟 problem+json 错误，使页面走与真实接口相同的错误展示路径。 */
function problem(status: number, type: string, title: string): HttpError {
  return new HttpError({ status, message: title, problem: { type, title, status } })
}

export interface MockSeedOp {
  operationNo: string
  name?: string
  workCenterCode?: string
  status?: OperationTaskStatus
}

/**
 * 【MOCK】内存模拟实现，状态机按设计稿 §3；不记录幂等，仅模拟状态冲突与版本冲突。
 * seed 提供工单的工序（默认 10 READY、20 PENDING）。
 */
export function createMockDispatchApi(seed: (wo: string) => MockSeedOp[] = () => [
  { operationNo: '10', status: 'READY' }, { operationNo: '20', status: 'PENDING' }
]): DispatchApi {
  const store = new Map<string, OperationTask[]>()
  const tasks = (wo: string) => {
    if (!store.has(wo)) {
      store.set(wo, seed(wo).map((s) => ({
        workOrderNo: wo, operationNo: s.operationNo, name: s.name ?? '', workCenterCode: s.workCenterCode ?? '',
        status: s.status ?? 'PENDING', stationCode: null, operator: null, plannedStart: null, plannedEnd: null,
        dispatchedAt: null, dispatchedBy: null, version: 0
      })))
    }
    return store.get(wo) as OperationTask[]
  }
  const find = (wo: string, op: string) => {
    const t = tasks(wo).find((x) => x.operationNo === op)
    if (!t) throw problem(404, 'operation-not-found', '工序不存在')
    return t
  }
  const conflict = () => problem(409, 'task-state-conflict', '工序任务状态冲突')
  const bump = (t: OperationTask) => { t.version += 1; return { ...t } }
  const assign = (t: OperationTask, r: DispatchRequest) => {
    if (!r.stationCode) throw problem(422, 'station-not-found', '工位不存在')
    t.stationCode = r.stationCode
    t.operator = r.operator ?? null
    t.plannedStart = r.plannedStart ?? null
    t.plannedEnd = r.plannedEnd ?? null
    t.dispatchedAt = new Date().toISOString()
  }
  return {
    listTasks: async (wo) => tasks(wo).map((t) => ({ ...t })),
    queryTasks: async (q) => [...store.values()].flat()
      .filter((t) => (!q.station || t.stationCode === q.station) && (!q.status?.length || q.status.includes(t.status)) && (!q.operator || t.operator === q.operator))
      .map((t) => ({ ...t })),
    async dispatch(wo, op, req) {
      const t = find(wo, op)
      if (!taskActions(t).dispatch) throw conflict()
      assign(t, req)
      return bump(t)
    },
    async reassign(wo, op, req) {
      const t = find(wo, op)
      if (!isDispatched(t)) throw problem(409, 'task-not-dispatched', '工序未派工')
      if (!taskActions(t).reassign) throw conflict()
      if (req.expectedVersion !== t.version) throw problem(409, 'concurrent-modification', '数据已被修改')
      assign(t, req)
      return bump(t)
    },
    async cancelDispatch(wo, op) {
      const t = find(wo, op)
      if (!isDispatched(t)) throw problem(409, 'task-not-dispatched', '工序未派工')
      if (!taskActions(t).cancelDispatch) throw conflict()
      t.stationCode = null; t.operator = null; t.plannedStart = null; t.plannedEnd = null
      return bump(t)
    },
    async start(wo, op) {
      const t = find(wo, op)
      if (!isDispatched(t)) throw problem(409, 'task-not-dispatched', '工序未派工')
      if (t.status !== 'READY') throw conflict()
      t.status = 'RUNNING'
      return bump(t)
    },
    async pause(wo, op) {
      const t = find(wo, op)
      if (t.status !== 'RUNNING') throw conflict()
      t.status = 'PAUSED'
      return bump(t)
    },
    async resume(wo, op) {
      const t = find(wo, op)
      if (t.status !== 'PAUSED') throw conflict()
      t.status = 'RUNNING'
      return bump(t)
    },
    listOperators: async (q) => [
      { id: 'op1', employeeNo: 'E001', name: '操作员一' },
      { id: 'op2', employeeNo: 'E002', name: '操作员二' },
      { id: 'op3', employeeNo: null, name: 'worker3' }
    ].filter((o) => !q || o.name.includes(q) || (o.employeeNo ?? '').includes(q))
  }
}

/** 是否使用模拟实现（默认是；VITE_DISPATCH_MOCK=false 时切换真实接口）。 */
export const DISPATCH_IS_MOCK = import.meta.env.VITE_DISPATCH_MOCK !== 'false'

let current: DispatchApi = DISPATCH_IS_MOCK ? createMockDispatchApi() : createHttpDispatchApi()

/** 当前派工实现。 */
export function dispatchApi(): DispatchApi {
  return current
}

/** 替换派工实现（测试或按工单种子数据时使用）。 */
export function setDispatchApi(api: DispatchApi) {
  current = api
}
