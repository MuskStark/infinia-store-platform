/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // Vite rebuilds the entry <script> tag and drops data-cfasync from
    // index.html, so re-add it here: without it Cloudflare Rocket Loader
    // rewrites type="module" into its own loader (a known SPA breaker).
    {
      name: 'entry-script-opts-out-of-rocket-loader',
      transformIndexHtml(html: string) {
        return html.replace(
          /<script(?![^>]*data-cfasync)([^>]*type="module")/g,
          '<script data-cfasync="false"$1',
        );
      },
    },
  ],
  resolve: {
    // Magic UI / shadcn registry sources import from "@/lib/utils" — keep the
    // alias so vendored components stay byte-identical to upstream.
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
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
    // Globals so @testing-library/react auto-cleans the DOM between tests.
    globals: true,
    setupFiles: ['./tests/setup.ts'],
  },
});
