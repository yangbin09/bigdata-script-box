import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

// Element Plus default size; we keep a flat, dense dev-tool aesthetic.
app.use(ElementPlus, { size: 'default' })
app.use(createPinia())
app.use(router)

// Register all icons globally so templates can use <el-icon><Plus /></el-icon> freely.
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, comp)
}

app.mount('#app')