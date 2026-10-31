import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期走 vite 代理到后端 8080，前端请求 /api/* 不用关心跨域。
// 后端也配了 CORS（dash.web.cors-origins），两条路都能通，代理是主路径。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // Agent 前端独立放在 agent/agent-frontend，主前端通过别名引用
      '@agent': fileURLToPath(new URL('../agent/agent-frontend/src', import.meta.url)),
      '@app': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
