<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useAuthStore } from './stores/auth';
import { setLocale, type Locale } from './i18n';
import BeeLevelBadge from './components/BeeLevelBadge.vue';

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

function submitSearch() {
  router.push({ name: 'browse', query: searchQuery.value ? { q: searchQuery.value } : {} });
}

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
const initial = computed(() =>
  (auth.user?.displayName ?? auth.user?.email ?? '?').charAt(0).toUpperCase(),
);
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <!-- Marketplace shell: the near-black bar is the signature element; it stays
         dark in both themes so the Infinia mark and white nav pop. -->
    <header class="header-bar sticky top-0 z-40">
      <div class="mx-auto flex max-w-7xl items-center gap-4 px-4 py-2.5">
        <RouterLink :to="{ name: 'discover' }" class="flex shrink-0 items-center gap-2">
          <!-- Official Infinia mark, shared with the FengYu host frontend. -->
          <img src="/infinia-logo.svg" alt="" class="h-8 w-8" />
          <span class="text-base tracking-tight">
            <span class="font-bold">Infinia</span>
            <span class="ml-1.5 font-light text-white/85">Store</span>
          </span>
        </RouterLink>

        <nav
          class="flex min-w-0 items-center gap-0.5 overflow-x-auto text-sm text-white/80"
          aria-label="primary"
        >
          <!-- inline-flex + items-center: the global 44px touch-target rule
               stretches these boxes taller than their text, so the label must
               center inside the box to sit level with the brand logo. -->
          <RouterLink
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'discover' }"
          >
            {{ t('nav.discover') }}
          </RouterLink>
          <RouterLink
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'browse' }"
          >
            {{ t('nav.browse') }}
          </RouterLink>
          <RouterLink
            v-if="auth.isAuthenticated"
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'library' }"
          >
            {{ t('nav.library') }}
          </RouterLink>
          <RouterLink
            v-if="isPublisher"
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'publisher' }"
          >
            {{ t('nav.publisher') }}
          </RouterLink>
          <RouterLink
            v-if="isStaff"
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'review' }"
          >
            {{ t('nav.review') }}
          </RouterLink>
          <RouterLink
            v-if="isAdmin"
            class="inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-white/10 hover:text-white"
            active-class="text-white font-semibold"
            :to="{ name: 'admin' }"
          >
            {{ t('nav.admin') }}
          </RouterLink>
        </nav>

        <form
          class="ml-auto hidden w-full max-w-64 md:block"
          @submit.prevent="submitSearch"
        >
          <label class="sr-only" for="global-search">{{ t('common.search') }}</label>
          <div class="relative">
            <input
              id="global-search"
              v-model="searchQuery"
              type="search"
              :placeholder="t('common.search')"
              class="w-full rounded-lg border border-white/20 bg-white/10 px-3 py-1.5 text-sm text-white placeholder:text-white/50 focus:border-white/60 focus:outline-none"
            />
            <svg
              class="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-white/70"
              width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"
            >
              <circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.6" />
              <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            </svg>
          </div>
        </form>

        <div class="flex shrink-0 items-center gap-0.5">
          <!-- Icon actions share one ghost style tuned for the dark bar. -->
          <button
            class="inline-flex items-center justify-center gap-1.5 rounded-lg px-2.5 py-2 text-sm text-white/80 transition-colors hover:bg-white/10 hover:text-white"
            :aria-label="t('common.language')"
            :title="t('common.language')"
            @click="switchLocale"
          >
            <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path
                d="M3 6h9M7.5 4v2M10 6c-.5 3.5-3 6.5-6 8M5 9.5c1.5 2.5 4 4.5 6.5 5M11.5 16l3.5-8 3.5 8M12.8 13.5h4.4"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="text-xs font-semibold">
              {{ locale === 'en' ? '中' : 'EN' }}
            </span>
          </button>
          <button
            class="inline-flex items-center justify-center rounded-lg px-2.5 py-2 text-white/80 transition-colors hover:bg-white/10 hover:text-white"
            :aria-label="t('common.theme')"
            :title="t('common.theme')"
            @click="toggleTheme"
          >
            <svg v-if="isDark" width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <circle cx="10" cy="10" r="3.5" stroke="currentColor" stroke-width="1.5" />
              <path
                d="M10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M15.3 4.7l-1.4 1.4M6.1 13.9l-1.4 1.4"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
              />
            </svg>
            <svg v-else width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path
                d="M17 12.5A7.5 7.5 0 0 1 7.5 3 7.5 7.5 0 1 0 17 12.5Z"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>

          <span class="mx-1.5 hidden h-6 w-px bg-white/20 sm:block" aria-hidden="true" />

          <!-- Anonymous: sign-in CTA. Signed-in: the account popover. -->
          <button
            v-if="!auth.isAuthenticated"
            class="btn btn-primary ml-1"
            @click="router.push({ name: 'signin' })"
          >
            {{ t('nav.signIn') }}
          </button>
          <div v-else ref="menuRoot" class="relative">
            <button
              class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-white/90 transition-colors hover:bg-white/10"
              aria-haspopup="menu"
              :aria-expanded="menuOpen"
              @click="toggleMenu"
            >
              <span
                class="grid h-7 w-7 place-items-center rounded-lg text-xs font-bold text-white"
                style="background: var(--hero-gradient)"
              >
                {{ initial }}
              </span>
              <span class="hidden max-w-36 truncate md:inline">{{ auth.user?.displayName }}</span>
              <BeeLevelBadge :level="auth.user?.beeLevel ?? 0" compact />
              <svg
                class="text-white/60 transition-transform"
                :class="menuOpen ? 'rotate-180' : ''"
                width="12"
                height="12"
                viewBox="0 0 12 12"
                fill="none"
                aria-hidden="true"
              >
                <path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <div
              v-if="menuOpen"
              class="absolute right-0 z-50 mt-2 w-64 overflow-hidden rounded-xl border border-line bg-surface shadow-xl shadow-slate-900/10 ring-1 ring-black/5 dark:border-slate-800 dark:bg-slate-900 dark:shadow-black/40 dark:ring-white/5"
              role="menu"
            >
              <div class="border-b border-line px-4 py-3 dark:border-slate-800">
                <p class="truncate text-sm font-semibold">{{ auth.user?.displayName }}</p>
                <p class="truncate text-xs text-muted">{{ auth.user?.email }}</p>
                <p class="mt-1.5">
                  <BeeLevelBadge :level="auth.user?.beeLevel ?? 0" />
                </p>
              </div>
              <div class="p-1.5">
                <button
                  class="block w-full rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted dark:hover:bg-slate-800"
                  role="menuitem"
                  @click="goUserCenter"
                >
                  {{ t('nav.account') }}
                </button>
                <RouterLink
                  class="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted dark:hover:bg-slate-800"
                  :to="{ name: 'library' }"
                  role="menuitem"
                  @click="menuOpen = false"
                >
                  {{ t('nav.library') }}
                </RouterLink>
                <RouterLink
                  v-if="isPublisher"
                  class="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted dark:hover:bg-slate-800"
                  :to="{ name: 'publisher' }"
                  role="menuitem"
                  @click="menuOpen = false"
                >
                  {{ t('nav.publisher') }}
                </RouterLink>
                <RouterLink
                  v-if="isAdmin"
                  class="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted dark:hover:bg-slate-800"
                  :to="{ name: 'admin' }"
                  role="menuitem"
                  @click="menuOpen = false"
                >
                  {{ t('nav.admin') }}
                </RouterLink>
              </div>
              <div class="border-t border-line p-1.5 dark:border-slate-800">
                <button
                  class="block w-full rounded-lg px-2.5 py-2 text-left text-sm text-danger hover:bg-danger/10 dark:text-red-400"
                  role="menuitem"
                  @click="goSignOut"
                >
                  {{ t('nav.signOut') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-7xl flex-1 px-4 py-8">
      <RouterView :key="route.fullPath" />
    </main>

    <!-- Light-gray marketplace footer band: links left, brand right. -->
    <footer class="border-t border-line bg-surface-muted dark:border-slate-800 dark:bg-slate-950">
      <div
        class="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-5 text-xs text-muted dark:text-slate-400"
      >
        <p>
          {{ t('common.footerTagline') }}
          <span aria-hidden="true">·</span>
          <RouterLink
            class="ml-1 text-accent hover:underline"
            :to="{ name: 'status' }"
          >
            {{ t('nav.status') }}
          </RouterLink>
        </p>
        <RouterLink :to="{ name: 'discover' }" class="flex items-center gap-2">
          <img src="/infinia-logo.svg" alt="" class="h-6 w-6" />
          <span class="font-semibold text-ink dark:text-slate-200">Infinia Store</span>
        </RouterLink>
      </div>
    </footer>
  </div>
</template>
