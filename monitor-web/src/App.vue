<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { setLocale, type Locale } from './i18n';
import StatusView from './views/StatusView.vue';

const { t, locale } = useI18n();

function toggleLocale() {
  setLocale((locale.value === 'en' ? 'zh-CN' : 'en') as Locale);
}

function toggleTheme() {
  const dark = document.documentElement.classList.toggle('dark');
  localStorage.setItem('infinia.monitor.theme', dark ? 'dark' : 'light');
}
</script>

<template>
  <div class="min-h-full">
    <!-- Minimal chrome: this app is one page, the status page. -->
    <header class="header-bar">
      <div class="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
        <div class="flex items-center gap-2.5">
          <img src="/infinia-logo.svg" alt="" class="h-6 w-6" />
          <div class="leading-tight">
            <div class="text-sm font-bold">{{ t('app.title') }}</div>
            <div class="text-[11px] text-slate-400">{{ t('app.statusTitle') }}</div>
          </div>
        </div>
        <div class="flex items-center gap-1">
          <button type="button" class="btn btn-ghost btn-sm" :aria-label="t('app.themeToggle')" @click="toggleTheme">
            <span aria-hidden="true">◐</span>
          </button>
          <button type="button" class="btn btn-ghost btn-sm" @click="toggleLocale">
            {{ locale === 'en' ? '中' : 'EN' }}
          </button>
        </div>
      </div>
    </header>
    <main class="mx-auto max-w-5xl px-4 py-8">
      <StatusView />
    </main>
  </div>
</template>
