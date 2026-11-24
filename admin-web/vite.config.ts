import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const apiTarget = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/api/v1': apiTarget,
      '/actuator/health/readiness': apiTarget,
    },
  },
})
