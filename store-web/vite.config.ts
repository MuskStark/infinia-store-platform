/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Absolute origin for the hashed /assets/** files (e.g. https://assets.infinia.fyi/)
// when the SPA's static files are published to Cloudflare Pages — empty keeps
// them same-origin. Must match the value the server's image build bakes in
// (docker-compose ASSETS_BASE_URL); different values change the bundle bytes
// and therefore the content hashes.
const assetsBaseUrl = process.env.ASSETS_BASE_URL
  ? process.env.ASSETS_BASE_URL.replace(/\/*$/, '/')
  : '/';

export default defineConfig({
  base: assetsBaseUrl,
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
      // Keep browser-session redirects on Vite, where the current sign-in UI lives.
      '/oauth2': { target: 'http://localhost:8080', autoRewrite: true },
      '/login': { target: 'http://localhost:8080', autoRewrite: true },
    },
  },
  test: {
    environment: 'jsdom',
    // Globals so @testing-library/react auto-cleans the DOM between tests.
    globals: true,
    setupFiles: ['./tests/setup.ts'],
  },
});
