// 应用入口：注册 Pinia、路由与 Element Plus（中文语言包）。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { setupHttp } from './api/http'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 拦截器依赖路由与 store，需在二者注册后再安装
setupHttp(router)
app.mount('#app')
