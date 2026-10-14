<!-- 主布局：侧边栏 + 顶栏；窄屏（<1024px，竖屏平板）自动折叠侧栏，菜单项高度 56px 方便手指点击。 -->
<template>
  <el-container class="layout">
    <el-aside
      :width="collapsed ? '64px' : '200px'"
      class="aside"
    >
      <div class="logo">
        {{ collapsed ? 'MES' : '智能制造系统' }}
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        router
        class="menu"
      >
        <el-menu-item index="/workorders">
          <el-icon><Document /></el-icon><template #title>
            工单
          </template>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><EditPen /></el-icon><template #title>
            报工
          </template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-button
          class="touch"
          text
          aria-label="切换菜单"
          @click="collapsed = !collapsed"
        >
          <el-icon size="22">
            <Fold />
          </el-icon>
        </el-button>
        <span class="page-title">{{ route.meta.title }}</span>
        <span class="spacer" />
        <span class="user">{{ auth.user?.username }}</span>
        <el-button
          class="touch logout"
          @click="logout"
        >
          退出
        </el-button>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document, EditPen, Fold } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)

/** 根据视口宽度决定是否折叠侧栏。 */
function onResize() {
  collapsed.value = window.innerWidth < 1024
}
onMounted(() => { onResize(); window.addEventListener('resize', onResize) })
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

/** 退出只丢弃本地令牌（后端短期令牌不即时吊销）。 */
function logout() {
  auth.clear()
  router.replace({ name: 'login' })
}

defineExpose({ collapsed, logout })
</script>

<style scoped>
.layout { min-height: 100vh; }
.aside { background: #001529; transition: width 0.2s; overflow: hidden; }
.logo { color: #fff; height: 56px; line-height: 56px; text-align: center; font-weight: 600; white-space: nowrap; }
.menu { border-right: none; --el-menu-item-height: 56px; --el-menu-bg-color: #001529; --el-menu-text-color: #cfd8e3; --el-menu-active-color: #409eff; }
.header { display: flex; align-items: center; gap: 12px; border-bottom: 1px solid #ebeef5; background: #fff; }
.page-title { font-size: 18px; font-weight: 600; }
.spacer { flex: 1; }
.main { overflow-x: hidden; }
.touch { min-height: 48px; min-width: 48px; font-size: 16px; }
</style>
