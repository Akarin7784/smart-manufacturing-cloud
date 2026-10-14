import { createRouter, createWebHistory, createMemoryHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 路由表：登录页公开，其余页面挂在主布局下并要求登录。 */
export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/workorders',
    children: [
      { path: 'workorders', name: 'workorders', component: () => import('@/views/WorkOrderView.vue'), meta: { title: '工单' } },
      { path: 'workorders/:id', name: 'workorder-detail', component: () => import('@/views/WorkOrderDetailView.vue'), meta: { title: '工单详情' } },
      { path: 'reports', name: 'reports', component: () => import('@/views/ReportView.vue'), meta: { title: '报工' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

/** 安装登录守卫：未登录访问受保护页面时跳转登录并带上回跳地址；已登录访问登录页则回首页。 */
export function installGuard(router: Router) {
  router.beforeEach((to) => {
    const auth = useAuthStore()
    if (!to.meta.public && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.name === 'login' && auth.isAuthenticated) return { path: '/' }
    return true
  })
  return router
}

/** 创建路由实例；测试环境使用内存历史。 */
export function createAppRouter(memory = false): Router {
  return installGuard(createRouter({ history: memory ? createMemoryHistory() : createWebHistory(), routes }))
}

export default createAppRouter()
