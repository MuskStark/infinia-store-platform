import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    // 8089 keeps the store SPA clear of the FengYu frontend's default 5173,
    // so both apps can run side by side during integration work.
    port: 8089,
    strictPort: true,
    proxy: {
      // Same-origin API + authorization server during development.
      '/api': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/login': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
} as ReturnType<typeof defineConfig>);
