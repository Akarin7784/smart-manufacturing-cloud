// 测试用的派工后端替身：作为 axios adapter 拦截派工 / 操作工 / 任务查询请求，状态机与 PR #68 一致，
// 失败时返回 application/problem+json，从而让页面走真实的请求层与错误解析路径。
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig, type AxiosResponse } from 'axios'
import { http } from '@/api/http'
import type { OperationTask, OperationTaskStatus } from '@/api/dispatch'

export interface FakeBackend {
  tasks: OperationTask[]
  calls: InternalAxiosRequestConfig[]
  operators: { id: string; employeeNo: string | null; name: string }[]
  /** 让下一次指定动作失败（409/422 返回 problem+json；0 模拟网络错误；其他如 503 为服务不可用）。 */
  failNext(action: string, status: number, type?: string): void
  /** 供 vi.mocked(getProgress) 返回带派工信息的进度。 */
  progress(wo: string): { workOrderNo: string; status: string; plannedQty: string; operations: Record<string, unknown>[] }
}

export function installFakeDispatchBackend(seed: { operationNo: string; status?: OperationTaskStatus }[] = [
  { operationNo: '10', status: 'READY' }, { operationNo: '20', status: 'PENDING' }
]): FakeBackend {
  const tasks: OperationTask[] = seed.map((s) => ({
    workOrderNo: 'WO1', operationNo: s.operationNo, name: '', workCenterCode: '', status: s.status ?? 'PENDING',
    stationCode: null, operator: null, plannedStart: null, plannedEnd: null, dispatchedAt: null, dispatchedBy: null, version: 0
  }))
  const failures = new Map<string, { status: number; type?: string }>()
  const fake: FakeBackend = {
    tasks, calls: [],
    failNext: (action, status, type) => { failures.set(action, { status, type }) },
    operators: [{ id: 'op1', employeeNo: 'E001', name: '操作员一' }, { id: 'op3', employeeNo: null, name: 'worker3' }],
    progress: (wo) => ({
      workOrderNo: wo, status: 'IN_PROGRESS', plannedQty: '10.000000',
      operations: tasks.map((t, i) => ({
        operationNo: t.operationNo, seq: i + 1, goodQty: '0', defectQty: '0', scrapQty: '0', uom: null, limit: '10', effectiveReports: 0,
        laborHours: '0.00', taskStatus: t.status, stationCode: t.stationCode, operator: t.operator, plannedStart: null, plannedEnd: null, version: t.version
      }))
    })
  }
  const ok = (config: InternalAxiosRequestConfig, data: unknown): AxiosResponse =>
    ({ status: 200, statusText: 'OK', headers: {}, config, data: { code: 'OK', message: 'success', data } })
  const fail = (config: InternalAxiosRequestConfig, status: number, type: string, detail: string) => {
    const response = { status, statusText: '', config, headers: new AxiosHeaders({ 'content-type': 'application/problem+json' }), data: { type, title: type, status, detail } }
    return new AxiosError(detail, String(status), config, null, response as AxiosResponse)
  }
  http.defaults.adapter = async (config) => {
    fake.calls.push(config)
    const url = config.url ?? ''
    if (url === '/auth/operators') return ok(config, fake.operators)
    if (url === '/operation-tasks') return ok(config, tasks.map((t) => ({ ...t })))
    const m = url.match(/^\/workorders\/([^/]+)\/operations\/([^/]+)\/([a-z-]+)$/)
    if (!m) throw fail(config, 404, 'not-found', url)
    const t = tasks.find((x) => x.operationNo === decodeURIComponent(m[2]))
    if (!t) throw fail(config, 404, 'operation-not-found', '工序不存在')
    const injected = failures.get(m[3])
    if (injected) {
      failures.delete(m[3])
      if (injected.status === 0) throw new AxiosError('Network Error', 'ERR_NETWORK', config)
      throw fail(config, injected.status, injected.type ?? 'service-unavailable', `注入失败 ${injected.status}`)
    }
    const body = config.data ? JSON.parse(config.data) : {}
    const dispatched = !!t.stationCode
    const conflict = () => fail(config, 409, 'task-state-conflict', `工序 ${t.operationNo}（${t.status}）状态冲突`)
    const notDispatched = () => fail(config, 409, 'task-not-dispatched', `工序 ${t.operationNo} 尚未派工`)
    switch (m[3]) {
      case 'dispatch':
      case 'reassign':
        if (m[3] === 'reassign') {
          if (!dispatched) throw notDispatched()
          if (t.status !== 'READY' && t.status !== 'PAUSED') throw conflict()
          if (body.expectedVersion !== t.version) throw fail(config, 409, 'concurrent-modification', '任务版本已变化')
        } else if (dispatched) throw conflict()
        if (!body.stationCode) throw fail(config, 400, 'validation-error', 'stationCode: 不能为空')
        if (body.stationCode === 'NOPE') throw fail(config, 422, 'station-not-found', '工位 NOPE 不存在')
        t.stationCode = body.stationCode; t.operator = body.operator ?? null
        break
      case 'cancel-dispatch':
        if (!dispatched) throw notDispatched()
        t.stationCode = null; t.operator = null
        break
      case 'start':
        if (!dispatched) throw notDispatched()
        if (t.status !== 'READY') throw conflict()
        t.status = 'RUNNING'
        break
      case 'pause':
        if (t.status !== 'RUNNING') throw conflict()
        t.status = 'PAUSED'
        break
      case 'resume':
        if (t.status !== 'PAUSED') throw conflict()
        t.status = 'RUNNING'
        break
      default:
        throw fail(config, 404, 'not-found', url)
    }
    t.version += 1
    return ok(config, { ...t })
  }
  return fake
}
