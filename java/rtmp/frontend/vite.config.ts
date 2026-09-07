import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '/static/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8888',
      '/stream-status': 'http://localhost:8888',
      '/stats': 'http://localhost:8888',
      '/version': 'http://localhost:8888',
      '/hls': 'http://localhost:8888',
      '/static': 'http://localhost:8888',
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler',
      },
    },
  },
})