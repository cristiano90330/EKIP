/** 配置前端构建和开发服务器，将产品 API 与 AI 健康请求转发给对应服务。 */

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') },
      '/ai':  { target: 'http://127.0.0.1:8000', changeOrigin: true, rewrite: p => p.replace(/^\/ai/, '') }
    }
  }
})
