// 测试公共工具：全局注册 Element Plus，并在每个用例前重置存储与 Pinia。
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
export const plugins = [ElementPlus]
export function freshPinia() {
  sessionStorage.clear()
  const p = createPinia()
  setActivePinia(p)
  return p
}
