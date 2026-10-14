// 保护工单 / 主数据 / 派工 HTTP 模块：路径、请求体、Idempotency-Key 与响应解包与契约一致。
import { describe, it, expect, beforeEach } from 'vitest'
import { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import * as wo from '@/api/workorders'
import { listStations } from '@/api/masterdata'
import { createHttpDispatchApi } from '@/api/dispatch'

let calls: InternalAxiosRequestConfig[] = []
let reply: (c: InternalAxiosRequestConfig) => unknown = () => null

beforeEach(() => {
  calls = []
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return { status: 200, statusText: '', headers: {}, config, data: { code: 'OK', message: 'success', data: reply(config) } }
  }
})
const last = () => calls[calls.length - 1]
const key = () => AxiosHeaders.from(last().headers).get('Idempotency-Key')

describe('工单 API', () => {
  it('查询类接口', async () => {
    reply = () => [{ id: 'WO1' }]
    expect(await wo.listWorkOrders()).toEqual([{ id: 'WO1' }])
    expect(last().url).toBe('/workorders')
    reply = () => ({ orderId: 'WO 1' })
    await wo.getRelease('WO 1')
    expect(last().url).toBe('/workorders/WO%201/release')
    await wo.getProgress('A')
    expect(last().url).toBe('/workorders/A/progress')
    await wo.getReports('A')
    expect(last().url).toBe('/workorders/A/reports')
  })
  it('写接口带幂等键与正确请求体', async () => {
    reply = () => ({})
    await wo.reportOperation('A', '10', { goodQty: '1', defectQty: '0', scrapQty: '0', laborHours: '0.50', uom: 'PCS', lotNo: null, note: null }, 'k1')
    expect(last().url).toBe('/workorders/A/operations/10/reports')
    expect(JSON.parse(last().data).laborHours).toBe('0.50')
    expect(key()).toBe('k1')
    await wo.releaseWorkOrder('A', 'k2')
    expect(last().url).toBe('/workorders/A/release'); expect(key()).toBe('k2')
    await wo.completeWorkOrder('A', '短缺', 'k3')
    expect(JSON.parse(last().data)).toEqual({ reason: '短缺' })
    await wo.holdWorkOrder('A', 'MANUAL')
    expect(JSON.parse(last().data)).toEqual({ reason: 'MANUAL', note: null })
    await wo.resumeWorkOrder('A', 'MANUAL', '好了')
    expect(last().url).toBe('/workorders/A/resume')
    await wo.createWorkOrder({ deviceId: 'D', product: 'P', quantity: 2 }, 'k4')
    expect(last().method).toBe('post'); expect(last().url).toBe('/workorders'); expect(key()).toBe('k4')
    expect(wo.newIdempotencyKey()).not.toBe(wo.newIdempotencyKey())
  })
  it('工位按工作中心过滤', async () => {
    reply = () => [{ code: 'WC1', stations: [{ code: 'S1' }] }, { code: 'WC2', stations: [{ code: 'S2' }] }, { code: 'WC3' }]
    expect((await listStations('WC2')).map((s) => s.code)).toEqual(['S2'])
    expect((await listStations()).map((s) => s.code)).toEqual(['S1', 'S2'])
    expect(last().url).toBe('/workcenters')
  })
})

describe('派工 HTTP 实现（契约对齐）', () => {
  const api = createHttpDispatchApi()
  it('各动作路径、请求体与幂等键', async () => {
    reply = () => ({ operationNo: '10' })
    await api.dispatch('A', '10', { stationCode: 'S1' }, 'k')
    expect(last().url).toBe('/workorders/A/operations/10/dispatch'); expect(key()).toBe('k')
    await api.reassign('A', '10', { stationCode: 'S2', expectedVersion: 3 }, 'k')
    expect(last().url).toMatch(/\/reassign$/); expect(JSON.parse(last().data).expectedVersion).toBe(3)
    await api.cancelDispatch('A', '10', '错派', 'k')
    expect(last().url).toMatch(/\/cancel-dispatch$/); expect(JSON.parse(last().data)).toEqual({ reason: '错派' })
    await api.start('A', '10', 'k')
    expect(last().url).toMatch(/\/start$/); expect(JSON.parse(last().data)).toEqual({ reason: null })
    await api.pause('A', '10', '换模', 'k')
    expect(JSON.parse(last().data)).toEqual({ reason: '换模' })
    await api.resume('A', '10', 'k', '继续')
    expect(last().url).toMatch(/\/resume$/)
  })
  it('任务查询与操作工', async () => {
    reply = () => []
    await api.queryTasks({ station: 'S1', status: ['READY', 'RUNNING'] })
    expect(last().url).toBe('/operation-tasks')
    expect(http.getUri(last())).toContain('status=READY&status=RUNNING')
    await api.listOperators('张')
    expect(last().url).toBe('/auth/operators'); expect(last().params).toEqual({ q: '张' })
    await api.listOperators()
    expect(last().params).toEqual({})
  })
  it('任务列表：派工信息取自 progress，工序名称与工作中心由 release 补齐', async () => {
    reply = (c) => c.url?.endsWith('/release')
      ? { operations: [{ opNo: '10', name: '车削', workCenterCode: 'WC1' }] }
      : { operations: [{ operationNo: '10', taskStatus: 'RUNNING', stationCode: 'S1', operator: 'op1', version: 2, laborHours: '1.50' }, { operationNo: '20' }] }
    const ts = await api.listTasks('A')
    expect(ts[0]).toMatchObject({ status: 'RUNNING', stationCode: 'S1', version: 2, name: '车削', workCenterCode: 'WC1' })
    expect(ts[1]).toMatchObject({ status: 'PENDING', stationCode: null, version: 0, name: '', workCenterCode: '' })
  })
  it('release 不可用（未下达 404）时任务列表降级', async () => {
    http.defaults.adapter = async (config) => {
      if (config.url?.endsWith('/release')) throw Object.assign(new Error('404'), { config })
      return { status: 200, statusText: '', headers: {}, config, data: { data: { operations: [{ operationNo: '10' }] } } }
    }
    expect((await api.listTasks('A'))[0]).toMatchObject({ operationNo: '10', workCenterCode: '' })
  })
})
