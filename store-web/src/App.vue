<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from './stores/auth';
import { setLocale, type Locale } from './i18n';
import { beeMark } from './bee-levels';

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
const menuOpen = ref(false);
const menuRoot = ref<HTMLElement | null>(null);

function toggleTheme() {
  isDark.value = !isDark.value;
  document.documentElement.classList.toggle('dark', isDark.value);
  localStorage.setItem('infinia.store.theme', isDark.value ? 'dark' : 'light');
}

function switchLocale() {
  setLocale((locale.value === 'en' ? 'zh-CN' : 'en') as Locale);
}

function toggleMenu() {
  menuOpen.value = !menuOpen.value;
}

/** Close on outside click / Escape so the menu behaves like a proper popover. */
function onDocumentClick(event: MouseEvent) {
  if (menuOpen.value && menuRoot.value && !menuRoot.value.contains(event.target as Node)) {
    menuOpen.value = false;
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    menuOpen.value = false;
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onKeydown);
});

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false;
  },
);

function goUserCenter() {
  menuOpen.value = false;
  router.push({ name: 'account' });
}

function goSignOut() {
  menuOpen.value = false;
  auth.signOut();
  router.push({ name: 'discover' });
}

const isPublisher = computed(() =>
  auth.roles.some((r) => ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'].includes(r)),
);
const isStaff = computed(() => auth.roles.some((r) => ['REVIEWER', 'PLATFORM_ADMIN'].includes(r)));
const isAdmin = computed(() => auth.roles.includes('PLATFORM_ADMIN'));
const levelMark = computed(() => beeMark(auth.user?.beeLevel ?? 0));
const initial = computed(() =>
  (auth.user?.displayName ?? auth.user?.email ?? '?').charAt(0).toUpperCase(),
);
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <header class="sticky top-0 z-40 border-b border-line bg-surface/85 backdrop-blur dark:border-slate-800 dark:bg-slate-950/85">
      <div class="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3">
        <RouterLink :to="{ name: 'discover' }" class="flex shrink-0 items-center gap-2 font-bold">
          <span class="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-accent to-accent2 text-white">
            ∞
          </span>
          <span class="hidden sm:inline">Infinia Store</span>
        </RouterLink>

        <nav class="flex min-w-0 items-center gap-0.5 overflow-x-auto text-sm" aria-label="primary">
          <RouterLink class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-surface-muted" :to="{ name: 'discover' }">
            {{ t('nav.discover') }}
          </RouterLink>
          <RouterLink class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-surface-muted" :to="{ name: 'browse' }">
            {{ t('nav.browse') }}
          </RouterLink>
          <RouterLink
            v-if="auth.isAuthenticated"
            class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-surface-muted"
            :to="{ name: 'library' }"
          >
            {{ t('nav.library') }}
          </RouterLink>
          <RouterLink
            v-if="isPublisher"
            class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-surface-muted"
            :to="{ name: 'publisher' }"
          >
            {{ t('nav.publisher') }}
          </RouterLink>
          <RouterLink
            v-if="isStaff"
            class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-surface-muted"
            :to="{ name: 'review' }"
          >
            {{ t('nav.review') }}
          </RouterLink>
          <RouterLink
            v-if="isAdmin"
            class="whitespace-nowrap rounded-lg px-2.5 py-2 hover:bg-slate-100 hover:text-accent dark:hover:bg-slate-800"
            :to="{ name: 'admin' }"
          >
            {{ t('nav.admin') }}
          </RouterLink>
        </nav>

        <form
          class="ml-auto w-full max-w-56 sm:w-52"
          @submit.prevent="router.push({ name: 'browse', query: searchQuery ? { q: searchQuery } : {} })"
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

        <div class="flex shrink-0 items-center gap-1">
          <button
            class="rounded-lg px-2.5 py-2 text-sm hover:bg-surface-muted"
            :aria-label="t('common.language')"
            @click="switchLocale"
          >
            {{ locale === 'en' ? '中' : 'EN' }}
          </button>
          <button
            class="rounded-lg px-2.5 py-2 text-sm hover:bg-surface-muted"
            :aria-label="t('common.theme')"
            @click="toggleTheme"
          >
            {{ isDark ? '☀' : '☾' }}
          </button>

          <!-- Anonymous: sign-in CTA. Signed-in: the account popover. -->
          <button
            v-if="!auth.isAuthenticated"
            class="rounded-xl bg-gradient-to-r from-accent to-accent2 px-4 py-2 text-sm font-semibold text-white"
            @click="router.push({ name: 'signin' })"
          >
            {{ t('nav.signIn') }}
          </button>
          <div v-else ref="menuRoot" class="relative">
            <button
              class="flex items-center gap-2 rounded-xl border border-line px-3 py-1.5 text-sm hover:bg-surface-muted dark:border-slate-800"
              aria-haspopup="menu"
              :aria-expanded="menuOpen"
              @click="toggleMenu"
            >
              <span
                class="grid h-7 w-7 place-items-center rounded-full bg-gradient-to-br from-accent to-accent2 text-xs font-bold text-white"
              >
                {{ initial }}
              </span>
              <span class="hidden max-w-36 truncate md:inline">{{ auth.user?.displayName }}</span>
              <span :title="t('beeLevel.title')">{{ levelMark.emblem }}</span>
              <span class="text-xs text-muted" aria-hidden="true">▾</span>
            </button>
            <div
              v-if="menuOpen"
              class="absolute right-0 z-50 mt-2 w-64 overflow-hidden rounded-2xl border border-line bg-surface shadow-xl dark:border-slate-800 dark:bg-slate-900"
              role="menu"
            >
              <div class="border-b border-line px-4 py-3 dark:border-slate-800">
                <p class="truncate text-sm font-semibold">{{ auth.user?.displayName }}</p>
                <p class="truncate text-xs text-muted">{{ auth.user?.email }}</p>
                <p class="mt-1 text-xs text-muted">
                  {{ levelMark.emblem }} {{ t(`beeLevel.${auth.user?.beeLevel ?? 0}`) }}
                  · Lv{{ auth.user?.beeLevel ?? 0 }}
                </p>
              </div>
              <button
                class="block w-full px-4 py-2.5 text-left text-sm hover:bg-surface-muted"
                role="menuitem"
                @click="goUserCenter"
              >
                {{ t('nav.account') }}
              </button>
              <RouterLink
                class="block px-4 py-2.5 text-left text-sm hover:bg-surface-muted"
                :to="{ name: 'library' }"
                role="menuitem"
                @click="menuOpen = false"
              >
                {{ t('nav.library') }}
              </RouterLink>
              <RouterLink
                v-if="isPublisher"
                class="block px-4 py-2.5 text-left text-sm hover:bg-surface-muted"
                :to="{ name: 'publisher' }"
                role="menuitem"
                @click="menuOpen = false"
              >
                {{ t('nav.publisher') }}
              </RouterLink>
              <RouterLink
                v-if="isAdmin"
                class="block px-4 py-2.5 text-left text-sm hover:bg-surface-muted"
                :to="{ name: 'admin' }"
                role="menuitem"
                @click="menuOpen = false"
              >
                {{ t('nav.admin') }}
              </RouterLink>
              <button
                class="block w-full border-t border-line px-4 py-2.5 text-left text-sm text-red-600 hover:bg-surface-muted dark:border-slate-800 dark:text-red-400"
                role="menuitem"
                @click="goSignOut"
              >
                {{ t('nav.signOut') }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-7xl flex-1 px-4 py-8">
      <RouterView :key="route.fullPath" />
    </main>

    <footer class="border-t border-line py-6 text-center text-xs text-muted dark:border-slate-800">
      {{ t('common.footerTagline') }}
    </footer>
  </div>
</template>
