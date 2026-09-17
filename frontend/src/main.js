import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  Aim,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Calendar,
  ChatDotRound,
  CircleCheck,
  Collection,
  Document,
  Expand,
  Fold,
  Loading,
  Plus,
  Refresh,
  Setting,
  Upload,
  UploadFilled,
  View,
  Warning,
  WarningFilled
} from '@element-plus/icons-vue'

// ElMessage 是函数式调用，走不了模板组件的自动导入，样式需要单独引这一份。
// 引入的是 message 单个组件的样式，而不是全量的 element-plus/dist/index.css。
import 'element-plus/es/components/message/style/css'

import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

// 只注册项目实际用到的图标（新增菜单/按钮用到图标时，记得在这里补一条）。
// 此前是 `import * as ElementPlusIconsVue` 把两百多个图标全量注册进主包。
const icons = {
  Aim,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Calendar,
  ChatDotRound,
  CircleCheck,
  Collection,
  Document,
  Expand,
  Fold,
  Loading,
  Plus,
  Refresh,
  Setting,
  Upload,
  UploadFilled,
  View,
  Warning,
  WarningFilled
}
for (const [name, component] of Object.entries(icons)) {
  app.component(name, component)
}

app.use(createPinia())
app.use(router)
app.mount('#app')
