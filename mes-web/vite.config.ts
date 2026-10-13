/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：开发时把 /api 代理到 Gateway，目标地址只从环境变量读取，避免把环境信息写死在代码里。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      proxy: {
        // 所有业务与认证请求统一经 Gateway 路由和 JWT 校验，前端不直连各微服务
        '/api': { target: env.VITE_GATEWAY_URL || 'http://localhost:8080', changeOrigin: true }
      }
    },
    test: {
      environment: 'jsdom',
      include: ['tests/**/*.spec.ts'],
      coverage: {
        provider: 'v8',
        // 输出 lcov 供 CI 的 diff 覆盖率检查使用
        reporter: ['text', 'lcov'],
        include: ['src/**/*.{ts,vue}'],
        exclude: ['src/main.ts', 'src/env.d.ts'],
        thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 }
      }
    }
  }
})
