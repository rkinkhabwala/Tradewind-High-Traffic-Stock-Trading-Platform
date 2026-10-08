/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Same origin as in production: the dev server proxies /api and /ws to the gateway, so the refresh cookie
// (path /api/auth, SameSite=Strict) works without CORS.
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: gateway, changeOrigin: false },
      '/ws': { target: gateway.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: {
    // *.nosync: the repository sits in an iCloud-synced folder that otherwise duplicates build output.
    outDir: 'dist.nosync',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    globals: true,
    setupFiles: ['src/test/setup.ts'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
});
