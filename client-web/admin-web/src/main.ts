import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/global.scss'
// 无障碍与响应式加固层：必须在 global.scss 之后，靠后者覆盖主题里的焦点样式。
import './styles/a11y.scss'

// 这里刻意**不**写 app.use(ElementPlus)。
//
// 全量注册会把整个组件库（约 700 kB）塞进主包，打包时无法摇掉未使用的组件；
// 组件与指令由 unplugin-vue-components 按需自动引入（见 vite.config.ts），
// 样式由上面那一行 index.css 统一提供（resolver 已设 importStyle: false）。
// 中文语言包改在 App.vue 里用 <el-config-provider :locale="zhCn"> 提供。
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
