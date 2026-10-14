import { ref } from 'vue'
import { HttpError } from '@/api/http'
import { parseFieldErrors, problemHint } from '@/utils/workorder'

/**
 * 页面级错误状态：把请求层抛出的 HttpError（problem+json 已解析）转换成可展示文本。
 * 403 时额外标记 forbidden，页面据此显示「无权限」而不是业务错误。
 */
export function useProblem() {
  const error = ref('')
  const forbidden = ref(false)
  // 字段级错误（validation-error），键为后端字段名
  const fieldErrors = ref<Record<string, string>>({})
  function capture(e: unknown) {
    fieldErrors.value = {}
    if (e instanceof HttpError) {
      if (typeof e.parsed.problem?.type === 'string' && e.parsed.problem.type.endsWith('validation-error')) {
        fieldErrors.value = parseFieldErrors(e.parsed.problem.detail)
      }
      forbidden.value = e.status === 403
      const hint = problemHint(e.parsed.problem?.type)
      error.value = e.status === 403 ? '无权限' : hint ? `${hint}（${e.message}）` : e.message
    } else {
      error.value = e instanceof Error ? e.message : '操作失败'
    }
  }
  function reset() {
    error.value = ''
    forbidden.value = false
    fieldErrors.value = {}
  }
  return { error, forbidden, fieldErrors, capture, reset }
}
