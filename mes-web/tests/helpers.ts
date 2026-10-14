// 视图测试公共工具：每次新建 pinia 与内存路由，登录指定权限后挂载到目标路径。
import { mount, flushPromises } from '@vue/test-utils'
import { vi, expect } from 'vitest'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { resetHttpForTest, setupHttp } from '@/api/http'
import { plugins, freshPinia } from './setup'

export async function mountAs(path: string, permissions: string[]) {
  const pinia = freshPinia()
  useAuthStore().setSession('t', 900, { username: 'u1', role: 'X', permissions })
  const router = createAppRouter(true)
  // 安装真实请求层拦截器（Bearer、problem+json 解析、401/403 处理），每次用例绑定新的路由
  resetHttpForTest()
  setupHttp(router)
  await router.push(path)
  await router.isReady()
  const w = mount(App, { global: { plugins: [...plugins, pinia, router] }, attachTo: document.body })
  await flushPromises()
  // 懒加载路由组件渲染完成后再交互
  await vi.waitFor(() => expect(w.find('.layout').exists()).toBe(true))
  return { w, router }
}

export function problemError(status: number, type: string, detail = '') {
  return { status, type, detail }
}
