// 保护工单展示与校验工具：状态映射、可执行动作、数量/工时格式、批次号规范化、problem 提示与字段错误解析。
import { describe, it, expect } from 'vitest'
import * as u from '@/utils/workorder'
import { canDispatchWith, canReportWith } from '@/utils/permissions'

describe('工单工具', () => {
  it('状态中文与标签颜色，未知状态原样', () => {
    expect(u.statusLabel('ON_HOLD')).toBe('已冻结')
    expect(u.statusLabel('X')).toBe('X')
    expect(u.statusTag('COMPLETED')).toBe('success')
    expect(u.statusTag('X')).toBe('info')
  })
  it('可报工与可执行动作', () => {
    expect(u.canReport('IN_PROGRESS')).toBe(true)
    expect(u.canReport('ON_HOLD')).toBe(false)
    expect(u.allowedActions('CREATED').release).toBe(true)
    expect(u.allowedActions('ON_HOLD').resume).toBe(true)
    expect(u.allowedActions('IN_PROGRESS')).toMatchObject({ hold: true, complete: true })
  })
  it('数量与工时格式', () => {
    expect(u.isValidQty('12.123456')).toBe(true)
    expect(u.isValidQty('1.1234567')).toBe(false)
    expect(u.isValidQty('-1')).toBe(false)
    expect(u.isValidHours('1.25')).toBe(true)
    expect(u.isValidHours('1.255')).toBe(false)
    expect(u.isValidHours('1234567')).toBe(false)
  })
  it('批次号去空白，空串为 null', () => {
    expect(u.normalizeLotNo('  L1 ')).toBe('L1')
    expect(u.normalizeLotNo('   ')).toBeNull()
    expect(u.normalizeLotNo(undefined)).toBeNull()
  })
  it('数量格式化与进度百分比', () => {
    expect(u.fmtQty('12.500000')).toBe('12.5')
    expect(u.fmtQty('3.000000')).toBe('3')
    expect(u.fmtQty(5)).toBe('5')
    expect(u.fmtQty(null)).toBe('-')
    expect(u.percent('5', '10')).toBe(50)
    expect(u.percent('20', '10')).toBe(100)
    expect(u.percent('1', '0')).toBe(0)
  })
  it('problem type 中文提示覆盖验收要求的类型', () => {
    for (const t of ['over-report', 'uom-mismatch', 'workorder-closed', 'validation-error', 'task-state-conflict', 'masterdata-in-use', 'task-not-dispatched', 'station-not-found']) {
      expect(u.problemHint(`https://x/problems/${t}`)).toBeTruthy()
      expect(u.problemHint(t)).toBeTruthy()
    }
    expect(u.problemHint('unknown')).toBeUndefined()
    expect(u.problemHint(1)).toBeUndefined()
  })
  it('解析字段错误', () => {
    expect(u.parseFieldErrors('goodQty: 必须大于等于0; uom: 不能为空; uom: 过长; 乱码')).toEqual({ goodQty: '必须大于等于0', uom: '不能为空；过长' })
    expect(u.parseFieldErrors(undefined)).toEqual({})
  })
  it('权限兼容 workorder:write', () => {
    const has = (ps: string[]) => (p: string) => ps.includes(p)
    expect(canReportWith(has(['execution:report']))).toBe(true)
    expect(canReportWith(has(['workorder:write']))).toBe(true)
    expect(canReportWith(has(['workorder:read']))).toBe(false)
    expect(canDispatchWith(has(['execution:dispatch']))).toBe(true)
    expect(canDispatchWith(has(['execution:report']))).toBe(false)
  })
})
