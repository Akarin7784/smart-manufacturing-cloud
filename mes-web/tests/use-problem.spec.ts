// #71：主数据 problem+json 经 useProblem 展示为引用冲突提示；validation-error 回显字段错误。
import { describe, it, expect } from 'vitest'
import { HttpError } from '@/api/http'
import { useProblem } from '@/composables/useProblem'
import { parseError } from '@/utils/problem'

describe('useProblem 与主数据 problem+json', () => {
  it('masterdata-in-use 显示引用冲突提示并附带后端 detail', () => {
    const p = useProblem()
    p.capture(new HttpError(parseError(409, 'application/problem+json', {
      type: 'masterdata-in-use', title: '主数据被引用', status: 409, detail: '物料 RM1 仍被 1 个未作废的 BOM 引用',
      code: 'MASTERDATA_IN_USE', data: { referenceType: 'BOM', references: ['FG/A01'] }
    })))
    expect(p.error.value).toBe('该主数据正在被引用，不能删除或停用，被BOM引用：FG/A01（物料 RM1 仍被 1 个未作废的 BOM 引用）')
    expect(p.forbidden.value).toBe(false)
  })
  it('masterdata-in-use 引用过多时截断并提示至少 N 个；data 缺失时退回通用提示', async () => {
    const p = useProblem()
    p.capture(new HttpError(parseError(409, 'application/problem+json', {
      type: 'masterdata-in-use', title: '主数据被引用', status: 409, detail: '工作中心 WC1 仍被引用',
      data: { referenceType: 'WORK_ORDER', references: Array.from({ length: 10 }, (_, i) => `WO${i}`) }
    })))
    expect(p.error.value).toContain('被工单引用：WO0、WO1、WO2 等至少 10 个')
    expect(p.error.value).toContain('工作中心 WC1 仍被引用')
    p.capture(new HttpError(parseError(409, 'application/problem+json', {
      type: 'masterdata-in-use', title: '主数据被引用', status: 409, detail: '仍被引用'
    })))
    expect(p.error.value).toBe('该主数据正在被引用，不能删除或停用（仍被引用）')
  })
  it('主数据 validation-error 回显字段错误', () => {
    const p = useProblem()
    p.capture(new HttpError(parseError(400, 'application/problem+json', {
      type: 'validation-error', title: '请求参数无效', status: 400, detail: 'code: 编码格式错误', code: 'INVALID_ARGUMENT'
    })))
    expect(p.fieldErrors.value).toEqual({ code: '编码格式错误' })
  })
})
