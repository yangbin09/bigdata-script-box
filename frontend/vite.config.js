import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Dev:  Vite serves at :5173, proxy /api/* -> Spring Boot :80.
// Prod: `npm run build` produces frontend/dist/, copied into src/main/resources/static/
//       by the Maven build. Relative base path so the SPA works under any prefix.
export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:80',
        changeOrigin: false
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // No code-splitting gymnastics; single chunk keeps the prod JAR's static/ flat.
    rollupOptions: {
      output: {
        manualChunks: undefined
      }
    }
  }
})