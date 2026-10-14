// 保护派工模拟实现的状态机（设计稿 §3）与错误类型，确保页面在后端上线前后行为一致。
import { describe, it, expect } from 'vitest'
import { createMockDispatchApi, taskActions, reassignBlockedReason, isDispatched, setDispatchApi, dispatchApi, type OperationTask } from '@/api/dispatch'

const t = (o: Partial<OperationTask>): OperationTask => ({ workOrderNo: 'A', operationNo: '10', name: '', workCenterCode: '', status: 'READY', stationCode: null, operator: null, plannedStart: null, plannedEnd: null, dispatchedAt: null, dispatchedBy: null, version: 0, ...o })

describe('派工动作规则', () => {
  it('按状态与是否派工给出动作', () => {
    expect(taskActions(t({}))).toMatchObject({ dispatch: true, start: false, reassign: false })
    expect(taskActions(t({ stationCode: 'S' }))).toMatchObject({ start: true, reassign: true, cancelDispatch: true })
    expect(taskActions(t({ stationCode: 'S', status: 'RUNNING' }))).toMatchObject({ pause: true, reassign: false })
    expect(taskActions(t({ stationCode: 'S', status: 'PAUSED' }))).toMatchObject({ resume: true, reassign: true, cancelDispatch: false })
    expect(isDispatched(t({ stationCode: 'S' }))).toBe(true)
  })
  it('改派不可用原因', () => {
    expect(reassignBlockedReason(t({}))).toContain('尚未派工')
    expect(reassignBlockedReason(t({ stationCode: 'S', status: 'RUNNING' }))).toContain('先暂停')
    expect(reassignBlockedReason(t({ stationCode: 'S', status: 'DONE' }))).toContain('仅待开工或已暂停')
  })
})

describe('模拟实现', () => {
  it('完整流程：派工→开工→暂停→改派→恢复→并发冲突', async () => {
    const api = createMockDispatchApi()
    await expect(api.start('A', '10', 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'task-not-dispatched' } } })
    await expect(api.reassign('A', '10', { stationCode: 'S', expectedVersion: 0 }, 'k')).rejects.toMatchObject({ status: 409 })
    await expect(api.cancelDispatch('A', '10', 'r', 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'task-not-dispatched' } } })
    await expect(api.dispatch('A', '10', { stationCode: '' }, 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'station-not-found' } } })
    let r = await api.dispatch('A', '10', { stationCode: 'S1', operator: 'op1' }, 'k')
    expect(r).toMatchObject({ stationCode: 'S1', version: 1 })
    await expect(api.dispatch('A', '10', { stationCode: 'S1' }, 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'task-state-conflict' } } })
    r = await api.start('A', '10', 'k'); expect(r.status).toBe('RUNNING')
    await expect(api.start('A', '10', 'k')).rejects.toMatchObject({ status: 409 })
    await expect(api.resume('A', '10', 'k')).rejects.toMatchObject({ status: 409 })
    await expect(api.reassign('A', '10', { stationCode: 'S2', expectedVersion: r.version }, 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'task-state-conflict' } } })
    await expect(api.cancelDispatch('A', '10', 'r', 'k')).rejects.toMatchObject({ status: 409 })
    r = await api.pause('A', '10', '换模', 'k'); expect(r.status).toBe('PAUSED')
    await expect(api.pause('A', '10', 'x', 'k')).rejects.toMatchObject({ status: 409 })
    await expect(api.reassign('A', '10', { stationCode: 'S2', expectedVersion: 0 }, 'k')).rejects.toMatchObject({ parsed: { problem: { type: 'concurrent-modification' } } })
    r = await api.reassign('A', '10', { stationCode: 'S2', expectedVersion: r.version }, 'k'); expect(r.stationCode).toBe('S2')
    r = await api.resume('A', '10', 'k'); expect(r.status).toBe('RUNNING')
    expect(await api.queryTasks({ station: 'S2', status: ['RUNNING'], operator: undefined })).toHaveLength(1)
    expect(await api.queryTasks({ operator: 'nobody' })).toHaveLength(0)
    await expect(api.start('A', '99', 'k')).rejects.toMatchObject({ status: 404 })
  })
  it('取消派工与操作工筛选', async () => {
    const api = createMockDispatchApi((wo) => [{ operationNo: '10', name: wo }])
    expect((await api.listTasks('B'))[0]).toMatchObject({ status: 'PENDING', name: 'B' })
    await api.dispatch('B', '10', { stationCode: 'S' }, 'k')
    expect((await api.cancelDispatch('B', '10', 'r', 'k')).stationCode).toBeNull()
    expect(await api.listOperators('E002')).toHaveLength(1)
    expect(await api.listOperators()).toHaveLength(2)
  })
  it('可替换实现', () => {
    const api = createMockDispatchApi()
    setDispatchApi(api)
    expect(dispatchApi()).toBe(api)
  })
})
