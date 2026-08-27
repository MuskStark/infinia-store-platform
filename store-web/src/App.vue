<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from './stores/auth';
import { setLocale, type Locale } from './i18n';

const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

// Plain ref, not a computed over document.documentElement: the DOM is not
// reactive, so a computed would cache its first value forever and the toggle
// would stop working after route changes (it flips only when the stale cache
// happens to differ from reality).
const isDark = ref(document.documentElement.classList.contains('dark'));
const searchQuery = ref('');

function toggleTheme() {
  isDark.value = !isDark.value;
  document.documentElement.classList.toggle('dark', isDark.value);
  localStorage.setItem('infinia.store.theme', isDark.value ? 'dark' : 'light');
}

function switchLocale() {
  setLocale((locale.value === 'en' ? 'zh-CN' : 'en') as Locale);
}

function signOut() {
  auth.signOut();
  router.push({ name: 'discover' });
}
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <header class="sticky top-0 z-40 border-b border-line bg-surface/85 backdrop-blur dark:border-slate-800 dark:bg-slate-950/85">
      <div class="mx-auto flex max-w-7xl flex-wrap items-center gap-3 px-4 py-3">
        <RouterLink :to="{ name: 'discover' }" class="flex items-center gap-2 font-bold">
          <span class="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-accent to-accent2 text-white">
            ∞
          </span>
          <span class="hidden sm:inline">Infinia Store</span>
        </RouterLink>

        <nav class="flex items-center gap-1 text-sm" aria-label="primary">
          <RouterLink class="rounded-lg px-3 py-2 hover:bg-surface-muted" :to="{ name: 'discover' }">
            {{ t('nav.discover') }}
          </RouterLink>
          <RouterLink class="rounded-lg px-3 py-2 hover:bg-surface-muted" :to="{ name: 'browse' }">
            {{ t('nav.browse') }}
          </RouterLink>
          <RouterLink
            v-if="auth.isAuthenticated"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'library' }"
          >
            {{ t('nav.library') }}
          </RouterLink>
          <RouterLink
            v-if="auth.isAuthenticated"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'organizations' }"
          >
            {{ t('nav.organizations') }}
          </RouterLink>
          <RouterLink
            v-if="auth.isAuthenticated"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'account' }"
          >
            {{ t('nav.account') }}
          </RouterLink>
          <RouterLink
            v-if="auth.roles.some((r) => ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'].includes(r))"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'publisher' }"
          >
            {{ t('nav.publisher') }}
          </RouterLink>
          <RouterLink
            v-if="auth.roles.some((r) => ['REVIEWER', 'PLATFORM_ADMIN'].includes(r))"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'review' }"
          >
            {{ t('nav.review') }}
          </RouterLink>
          <RouterLink
            v-if="auth.roles.includes('PLATFORM_ADMIN')"
            class="rounded-lg px-3 py-2 hover:bg-surface-muted"
            :to="{ name: 'admin' }"
          >
            {{ t('nav.admin') }}
          </RouterLink>
        </nav>

        <form
          class="order-last w-full sm:order-none sm:ml-auto sm:w-72"
          @submit.prevent="router.push({ name: 'browse' })"
        >
          <label class="sr-only" for="global-search">{{ t('common.search') }}</label>
          <input
            id="global-search"
            v-model="searchQuery"
            type="search"
            :placeholder="t('common.search')"
            class="w-full rounded-xl border border-line bg-surface px-4 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
          />
        </form>

        <div class="flex items-center gap-1">
          <button
            class="rounded-lg px-3 py-2 text-sm hover:bg-surface-muted"
            :aria-label="t('common.language')"
            @click="switchLocale"
          >
            {{ locale === 'en' ? '中' : 'EN' }}
          </button>
          <button
            class="rounded-lg px-3 py-2 text-sm hover:bg-surface-muted"
            :aria-label="t('common.theme')"
            @click="toggleTheme"
          >
            {{ isDark ? '☀' : '☾' }}
          </button>
          <button
            v-if="!auth.isAuthenticated"
            class="rounded-xl bg-gradient-to-r from-accent to-accent2 px-4 py-2 text-sm font-semibold text-white"
            @click="router.push({ name: 'signin' })"
          >
            {{ t('nav.signIn') }}
          </button>
          <button
            v-else
            class="rounded-lg px-3 py-2 text-sm hover:bg-surface-muted"
            @click="signOut"
          >
            {{ t('nav.signOut') }}
          </button>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-7xl flex-1 px-4 py-8">
      <RouterView :key="route.fullPath" />
    </main>

    <footer class="border-t border-line py-6 text-center text-xs text-muted dark:border-slate-800">
      Infinia Store Platform · Signed · Reviewed · Rollback-safe
    </footer>
  </div>
</template>
