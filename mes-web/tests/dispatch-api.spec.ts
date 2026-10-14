// 保护派工动作规则与操作工显示规则（与 PR #68 状态机一致）。
import { describe, it, expect } from 'vitest'
import { operatorLabel, taskActions, reassignBlockedReason, isDispatched, dispatchApi, type OperationTask } from '@/api/dispatch'

const t = (o: Partial<OperationTask>): OperationTask => ({ workOrderNo: 'A', operationNo: '10', name: '', workCenterCode: '', status: 'READY', stationCode: null, operator: null, plannedStart: null, plannedEnd: null, dispatchedAt: null, dispatchedBy: null, version: 0, ...o })

describe('派工动作规则', () => {
  it('按状态与是否派工给出动作', () => {
    expect(taskActions(t({}))).toMatchObject({ dispatch: true, start: false, reassign: false })
    expect(taskActions(t({ stationCode: 'S' }))).toMatchObject({ start: true, reassign: true, cancelDispatch: true })
    expect(taskActions(t({ stationCode: 'S', status: 'RUNNING' }))).toMatchObject({ pause: true, reassign: false })
    expect(taskActions(t({ stationCode: 'S', status: 'PAUSED' }))).toMatchObject({ resume: true, reassign: true, cancelDispatch: false })
    expect(isDispatched(t({ stationCode: 'S' }))).toBe(true)
  })
  it('操作工显示：工号为 null / 空白只显示姓名', () => {
    expect(operatorLabel({ id: '1', employeeNo: 'E1', name: '张三' })).toBe('E1 张三')
    expect(operatorLabel({ id: '2', employeeNo: null, name: 'worker' })).toBe('worker')
    expect(operatorLabel({ id: '3', employeeNo: '  ', name: '李四' })).toBe('李四')
    expect(operatorLabel({ id: '4', employeeNo: 'admin', name: 'admin' })).toBe('admin')
  })
  it('改派不可用原因', () => {
    expect(reassignBlockedReason(t({}))).toContain('尚未派工')
    expect(reassignBlockedReason(t({ stationCode: 'S', status: 'RUNNING' }))).toContain('先暂停')
    expect(reassignBlockedReason(t({ stationCode: 'S', status: 'DONE' }))).toContain('仅待开工或已暂停')
  })
  it('默认实例为真实 HTTP 实现（单例）', () => {
    expect(dispatchApi()).toBe(dispatchApi())
    expect(typeof dispatchApi().reassign).toBe('function')
  })
})
