import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    ...(mode === 'management' ? { port: 5173, strictPort: true } : {}),
    proxy: {
      '/api': {
        target: mode === 'management' ? 'http://127.0.0.1:8081' : 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
}))
