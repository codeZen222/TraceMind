import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/index.css'

const app = createApp(App)

/**
 * 全局渲染错误兜底。
 *
 * ★ 为什么必须有：Vue 在 dev 模式下会把组件渲染错误【重新抛出】。
 *   如果抛错时正好在 <RouterView> 切换页面（patch 过程中），
 *   这一次 patch 就会被中断 —— 表现是「点了菜单 URL 变了、页面不换，刷新才好」。
 *   挂上 errorHandler 后错误被这里接住，单个组件渲染失败不再拖垮整页和路由。
 *
 * 仍然 console.error 打出来，不做静默吞掉（方便定位真实问题）。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[vue error]', info, err)
}

app.use(createPinia())
app.use(router)
app.mount('#app')
