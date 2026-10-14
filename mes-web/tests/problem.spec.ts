// 保护 problem+json 解析与中文兜底提示的行为。
import { describe, it, expect } from 'vitest'
import { parseError, isProblemJson, defaultMessage, isProblemType } from '@/utils/problem'

describe('problem+json 解析', () => {
  it('识别带 charset 的 problem+json', () => {
    expect(isProblemJson('application/problem+json;charset=UTF-8')).toBe(true)
    expect(isProblemJson('application/json')).toBe(false)
    expect(isProblemJson(undefined)).toBe(false)
  })
  it('优先使用 detail，其次 title', () => {
    expect(parseError(400, 'application/problem+json', { title: 'T', detail: 'D', status: 409 })).toMatchObject({ status: 409, message: 'D' })
    expect(parseError(400, 'application/problem+json', { title: 'T' }).message).toBe('T')
    expect(parseError(403, 'application/problem+json', {}).message).toBe('无权限')
  })
  it('非 problem 响应退化为兜底提示', () => {
    expect(parseError(500, 'text/html', '<html>')).toEqual({ status: 500, message: '服务器异常，请稍后重试' })
  })
  it('各状态码兜底文案', () => {
    expect(defaultMessage(0)).toContain('网络')
    expect(defaultMessage(401)).toContain('登录')
    expect(defaultMessage(404)).toContain('不存在')
    expect(defaultMessage(418)).toBe('请求失败')
  })
  it('主数据被引用（#71）：按顶层 type 识别，data 携带引用明细', () => {
    const body = {
      type: 'masterdata-in-use', title: '主数据被引用', status: 409, detail: '工作中心 WC1 仍被 1 个未作废的工艺路线引用',
      instance: '/workcenters/WC1', code: 'MASTERDATA_IN_USE', data: { referenceType: 'ROUTING', references: ['FG/R01'] }
    }
    const parsed = parseError(409, 'application/problem+json', body)
    expect(parsed).toMatchObject({ status: 409, message: body.detail })
    expect(isProblemType(parsed.problem, 'masterdata-in-use')).toBe(true)
    expect(parsed.problem?.data).toEqual({ referenceType: 'ROUTING', references: ['FG/R01'] })
    expect(isProblemType({ type: 'https://x/problems/masterdata-in-use' }, 'masterdata-in-use')).toBe(true)
    expect(isProblemType(parsed.problem, 'validation-error')).toBe(false)
    expect(isProblemType(undefined, 'masterdata-in-use')).toBe(false)
    // 旧格式 {code,message,data:{type}} 不再被识别为引用冲突
    const legacy = parseError(409, 'application/json', { code: 'MASTERDATA_IN_USE', message: 'x', data: { type: 'masterdata-in-use' } })
    expect(isProblemType(legacy.problem, 'masterdata-in-use')).toBe(false)
  })
})
