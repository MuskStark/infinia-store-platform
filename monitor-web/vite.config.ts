import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    // 8091 sits next to the store SPA's 8089 and the monitor API's 8090.
    port: 8091,
    strictPort: true,
    proxy: {
      // Same-origin monitor API during development.
      '/api': 'http://localhost:8090',
    },
  },
  test: {
    environment: 'jsdom',
  },
} as ReturnType<typeof defineConfig>);
