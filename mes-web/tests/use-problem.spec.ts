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
    expect(p.error.value).toBe('该主数据正在被引用，不能删除或停用（物料 RM1 仍被 1 个未作废的 BOM 引用）')
    expect(p.forbidden.value).toBe(false)
  })
  it('主数据 validation-error 回显字段错误', () => {
    const p = useProblem()
    p.capture(new HttpError(parseError(400, 'application/problem+json', {
      type: 'validation-error', title: '请求参数无效', status: 400, detail: 'code: 编码格式错误', code: 'INVALID_ARGUMENT'
    })))
    expect(p.fieldErrors.value).toEqual({ code: '编码格式错误' })
  })
})
