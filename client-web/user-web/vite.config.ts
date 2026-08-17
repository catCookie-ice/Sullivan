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
