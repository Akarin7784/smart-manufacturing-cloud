// 保护 problem+json 解析与中文兜底提示的行为。
import { describe, it, expect } from 'vitest'
import { parseError, isProblemJson, defaultMessage } from '@/utils/problem'

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
})
