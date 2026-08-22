import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期走 vite 代理到后端 8080，前端请求 /api/* 不用关心跨域。
// 后端也配了 CORS（dash.web.cors-origins），两条路都能通，代理是主路径。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
