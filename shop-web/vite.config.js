import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：/api → 网关 8080（与规格 4.3「统一请求地址为网关地址」一致）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 说明：智能客服 Agent（Python :8000）**不再单独代理**，统一走 /api → 网关 8080。
      // 网关新增 /api/agent/** 路由转发到 8000，这样才能对 Agent 施加统一的鉴权与限流：
      // 直连 8000 时 user_id 只能由前端自报，任意用户即可读取他人会话记录。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
