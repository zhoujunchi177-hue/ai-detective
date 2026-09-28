import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 采用按需引入（见 vite.config.ts），这里只补一份基础样式变量。
import 'element-plus/theme-chalk/base.css'
import App from './App.vue'
import router from './router'
import { vReveal } from './directives/reveal'
import './assets/main.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
// 全局注册：滚动进入动画在列表页大量复用，逐个 import 太啰嗦
app.directive('reveal', vReveal)
app.mount('#app')
