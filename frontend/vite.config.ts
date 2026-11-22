import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 与 Spring Boot 共用 LIVEFLOW_PORT，让端口调整同时作用于代理目标。
const backend = `http://127.0.0.1:${process.env.LIVEFLOW_PORT || 18080}`

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    // 端口冲突时直接报错，保证前端 Origin 与后端的握手配置一致。
    strictPort: true,
    // 浏览器使用同源路径，开发服务器转发请求和 WebSocket 升级。
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/actuator/health': { target: backend, changeOrigin: true },
      '/ws': { target: backend, changeOrigin: true, ws: true },
    },
  },
})
