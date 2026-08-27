import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// 后端代理目标固定写死为 8642（server-java 实际监听端口）。
// 注意：禁止在此读取 server-java/.env 或任何后端文件——前端不得以请求以外的方式接触后端资源。
const apiTarget = 'http://127.0.0.1:8642'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // 跨应用共享包（keep_auto 表单草稿自动保存等）。
      '@shared': fileURLToPath(new URL('../shared', import.meta.url)),
      // 共享包中的 bare import 无法沿目录向上解析到本应用 node_modules，此处显式映射到本应用的依赖。
      // 注意：必须指向包目录（而非具体文件），否则 element-plus/es/... 等子路径会被错误替换成文件+子路径。
      'vue': fileURLToPath(new URL('./node_modules/vue', import.meta.url)),
      'vue-router': fileURLToPath(new URL('./node_modules/vue-router', import.meta.url)),
      'element-plus': fileURLToPath(new URL('./node_modules/element-plus', import.meta.url)),
    },
  },
  server: {
    port: 5175,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      // 实时长连接 WebSocket（握手带 ?token= 与 ?sessionId=）
      '/ws': {
        target: apiTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
