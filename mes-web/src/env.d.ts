/// <reference types="vite/client" />
// 让 TypeScript 识别 .vue 单文件组件
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
