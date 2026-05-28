import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3333,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8080', // 后端服务的地址
        changeOrigin: true, // 推荐设置
        secure: false, // 如果是https代理到http，需要设置这个参数
        // rewrite: (path) => path.replace(/^\/api/, ''), // 可选，如果你的后端不需要 /api 路径前缀
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  }
})
