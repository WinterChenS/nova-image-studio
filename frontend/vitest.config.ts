import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    // WIN-31 G-2: 重 UI 套件（CanvasEditor.routes 等）满载并行时偶发超过默认 5s
    // 超时（单跑约 1.6s，CPU 争抢下放大）——提高兑底上限消除 CI 偶发红。
    testTimeout: 15000,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
