import { ref } from 'vue'
import { HttpError } from '@/api/http'
import { parseFieldErrors, problemHint } from '@/utils/workorder'
import { formatInUse, isProblemType, parseInUse } from '@/utils/problem'

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
      if (isProblemType(e.parsed.problem, 'validation-error')) {
        fieldErrors.value = parseFieldErrors(e.parsed.problem?.detail)
      }
      forbidden.value = e.status === 403
      const hint = problemHint(e.parsed.problem?.type)
      // 主数据被引用：在通用提示后附上引用方类型与引用清单（#83），清单缺失时与其他类型一致
      const inUse = parseInUse(e.parsed.problem)
      const refs = inUse ? formatInUse(inUse) : ''
      const main = refs ? `${hint}，${refs}` : hint
      error.value = e.status === 403 ? '无权限' : main ? `${main}（${e.message}）` : e.message
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
