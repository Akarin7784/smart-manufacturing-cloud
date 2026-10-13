<!-- 登录页：调用 auth-service 登录，成功后保存令牌并跳回原页面；按钮与输入框加大以适配车间平板触控。 -->
<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1 class="title">
        智能制造系统（MES）
      </h1>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @submit.prevent="onSubmit"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          class="error"
        />
        <el-button
          type="primary"
          native-type="submit"
          class="submit"
          :loading="loading"
        >
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')
// 前端校验与后端 LoginRequest 约束保持一致（非空、长度上限）
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { max: 64, message: '用户名过长' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { max: 256, message: '密码过长' }]
}

/** 只允许站内相对路径回跳，防止 redirect 参数被利用做开放重定向。 */
function safeRedirect(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/'
}

async function onSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  // 双重保护：组件库校验之外再显式判空，避免空凭证请求后端
  if (!valid || !form.username.trim() || !form.password) return
  loading.value = true
  error.value = ''
  try {
    const t = await login({ username: form.username, password: form.password })
    auth.setSession(t.accessToken, t.expiresIn, { username: t.username, role: t.role, permissions: t.permissions })
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}

defineExpose({ onSubmit, form, error })
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f0f2f5; padding: 16px; }
.login-card { width: 100%; max-width: 420px; }
.title { text-align: center; font-size: 22px; margin: 8px 0 24px; }
.error { margin-bottom: 16px; }
/* 触控目标不小于 48px */
.submit { width: 100%; min-height: 48px; font-size: 18px; }
</style>
