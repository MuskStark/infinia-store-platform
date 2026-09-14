import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Absolute origin for the hashed /assets/** files (e.g.
// https://status-assets.infinia.fyi/) when this SPA's static files are
// published to a Cloudflare Pages project — empty keeps them same-origin.
// Must equal the value the monitor image build bakes in (docker-compose
// ASSETS_BASE_URL); different values change the bundle bytes and therefore
// the content hashes.
const assetsBaseUrl = process.env.ASSETS_BASE_URL
  ? process.env.ASSETS_BASE_URL.replace(/\/*$/, '/')
  : '/';

export default defineConfig({
  base: assetsBaseUrl,
  plugins: [react(), tailwindcss()],
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
    setupFiles: ['./tests/setup.ts'],
  },
} as ReturnType<typeof defineConfig>);
