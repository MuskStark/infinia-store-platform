<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { api, ApiRequestError, setAccessToken, type PublicUser } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { submitOAuthSessionLogin } from '../auth/sessionLogin';
import { MagicCard } from '@infinia/magic-ui-vue';
import HoneycombField from '../components/HoneycombField.vue';
import BeeCrest from '../components/BeeCrest.vue';

/**
 * Sign-in / registration (design §7.4).
 *
 * Normal Store sign-in uses the direct token endpoint. Host OAuth requests arrive
 * with ?oauth=1 and establish the Authorization Server browser session here before
 * Spring resumes the saved PKCE authorization request.
 */
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const mode = ref<'signin' | 'register'>('signin');
const busy = ref(false);

const email = ref('');
const password = ref('');
const passwordConfirm = ref('');
const displayName = ref('');
const showPassword = ref(false);

const oauthMode = computed(() => route.query.oauth === '1');
const error = ref<string | null>(route.query.error === '1' ? t('errors.invalid_credentials') : null);
const notice = ref<string | null>(null);

/** Seeded demo accounts exist only under local/dev profiles, and the panel
 *  only makes sense then: production seeds nothing. The literals sit behind
 *  the DEV constant so the production build tree-shakes them out entirely. */
const showDemoAccounts = import.meta.env.DEV;
const demoAccounts = showDemoAccounts
  ? computed(() => [
      { email: 'admin@infinia.local', password: 'Password123!', label: t('role.PLATFORM_ADMIN') },
      { email: 'reviewer@infinia.local', password: 'Password123!', label: t('role.REVIEWER') },
      { email: 'publisher@infinia.local', password: 'Password123!', label: t('role.PUBLISHER') },
      { email: 'user@infinia.local', password: 'Password123!', label: t('role.USER') },
    ])
  : computed(() => [] as { email: string; password: string; label: string }[]);

const emailInvalid = computed(() => email.value.length > 0 && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value));
const passwordShort = computed(() => mode.value === 'register' && password.value.length > 0 && password.value.length < 8);
const passwordMismatch = computed(() => passwordConfirm.value.length > 0 && passwordConfirm.value !== password.value);
const formInvalid = computed(() => {
  if (emailInvalid.value || !email.value || !password.value) return true;
  if (mode.value === 'register') {
    return passwordShort.value || passwordMismatch.value;
  }
  return false;
});

function switchMode(next: 'signin' | 'register') {
  mode.value = next;
  error.value = null;
  notice.value = null;
}

/** Fills the form from a demo account row; carries no literals itself so it
 *  stays in the bundle while the credentials do not. */
function useDemoAccount(demo: { email: string; password: string }) {
  mode.value = 'signin';
  error.value = null;
  notice.value = null;
  email.value = demo.email;
  password.value = demo.password;
}

function problemText(e: unknown): string {
  if (e instanceof ApiRequestError && e.code) {
    const localized = t(`errors.${e.code}`);
    const text = localized !== `errors.${e.code}` ? localized : (e.detail ?? e.message);
    // Validation failures keep their server-side cause (e.g. which field
    // failed) — the bare localized title alone is not actionable.
    if (e.code === 'validation_failed' && e.detail && !text.includes(e.detail)) {
      return `${text}：${e.detail}`;
    }
    return text;
  }
  return t('errors.server');
}

async function finishLogin(token: string, user?: PublicUser) {
  setAccessToken(token);
  // The login response already carries the user: navigate at once instead of
  // waiting on a second /me round-trip that leaves the form looking dead.
  if (user) {
    auth.adoptUser(user);
  } else {
    await auth.load();
  }
  const redirect = (route.query.redirect as string) ?? '/';
  try {
    await router.push(redirect);
  } catch {
    // A SPA navigation can fail after a redeploy (stale shell referencing
    // removed chunks). A full page load re-fetches the shell and still lands
    // the user where they asked to go — never stranded on the sign-in page.
    window.location.assign(redirect);
  }
}

async function signIn() {
  if (formInvalid.value || busy.value) return;
  busy.value = true;
  error.value = null;
  try {
    if (oauthMode.value) {
      await submitOAuthSessionLogin(email.value, password.value);
      return;
    }
    const response = await api.post<{ accessToken: string; user?: PublicUser }>('/api/v1/auth/login',
      { email: email.value, password: password.value },
    );
    await finishLogin(response.accessToken, response.user);
  } catch (e) {
    error.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

async function register() {
  if (formInvalid.value || busy.value) return;
  busy.value = true;
  error.value = null;
  notice.value = null;
  try {
    await api.post('/api/v1/auth/register', {
      email: email.value,
      password: password.value,
      displayName: displayName.value || undefined,
    });
    if (oauthMode.value) {
      await submitOAuthSessionLogin(email.value, password.value);
      return;
    }
    // Register → immediately signed in with the same credentials.
    const response = await api.post<{ accessToken: string; user?: PublicUser }>('/api/v1/auth/login', {
      email: email.value,
      password: password.value,
    });
    await finishLogin(response.accessToken, response.user);
  } catch (e) {
    error.value = problemText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="-mx-4 -my-8 lg:flex lg:min-h-[calc(100vh-9.5rem)]">
    <!-- Brand panel: the hive wall. Near-black in both themes like the header
         bar; the comb texture is the store's signature, not a wallpaper. -->
    <div class="relative hidden overflow-hidden bg-[#19191c] lg:flex lg:w-[44%] lg:items-center">
      <HoneycombField
        class="absolute inset-0"
        color="rgba(252, 128, 29, 0.45)"
        wax-color="#fc801d"
        :rows="6"
        :cols="7"
        :cell="52"
        :waxed="[5, 8, 25, 34]"
      />
      <div class="absolute inset-0 bg-gradient-to-t from-[#19191c] via-transparent to-[#19191c]/60" />

      <div class="relative px-12">
        <BeeCrest :level="4" :size="52" class="mb-6" />
        <p class="text-xs font-semibold uppercase tracking-[0.2em] text-white/40">
          Infinia Store
        </p>
        <h2 class="mt-3 text-4xl font-bold leading-tight tracking-tight text-white">
          {{ t('auth.brandTitle') }}
        </h2>
        <p class="mt-4 max-w-sm text-sm leading-6 text-white/55">
          {{ t('discover.heroSubtitle') }}
        </p>
      </div>
    </div>

    <!-- Form panel -->
    <div class="flex w-full items-center justify-center px-4 py-10 lg:flex-1">
      <div class="w-full max-w-md">
        <!-- Slim brand strip keeps the identity on mobile where the wall is hidden. -->
        <div class="mb-6 flex items-center gap-3 lg:hidden">
          <img src="/infinia-logo.svg" alt="" class="h-9 w-9" />
          <span class="text-base font-bold">Infinia Store</span>
        </div>

        <MagicCard class="p-8">
          <div class="mb-6 hidden items-center gap-3 lg:flex">
            <img src="/infinia-logo.svg" alt="" class="h-10 w-10" />
            <h1 class="text-xl font-bold">
              {{ mode === 'signin' ? t('auth.signInTitle') : t('auth.registerTitle') }}
            </h1>
          </div>
          <h1 class="mb-6 text-xl font-bold lg:hidden">
            {{ mode === 'signin' ? t('auth.signInTitle') : t('auth.registerTitle') }}
          </h1>

          <nav class="mb-6 flex gap-1 rounded-xl bg-surface-muted p-1" role="tablist" :aria-label="t('auth.signInTitle')">
            <button
              v-for="key in ['signin', 'register'] as const"
              :key="key"
              role="tab"
              :aria-selected="mode === key"
              class="flex-1 rounded-lg px-4 py-2 text-sm font-medium"
              :class="mode === key ? 'bg-surface shadow-sm dark:bg-slate-800' : 'text-muted'"
              @click="switchMode(key)"
            >
              {{ key === 'signin' ? t('nav.signIn') : t('auth.register') }}
            </button>
          </nav>

          <form class="space-y-4" novalidate @submit.prevent="mode === 'signin' ? signIn() : register()">
            <label class="block text-sm">
              {{ t('auth.email') }}
              <input
                v-model="email"
                type="email"
                required
                autocomplete="username"
                :aria-invalid="emailInvalid || undefined"
                placeholder="you@example.com"
                class="input mt-1"
              />
              <span v-if="emailInvalid" class="mt-1 block text-xs text-red-600 dark:text-red-400">
                {{ t('auth.emailInvalid') }}
              </span>
            </label>

            <label class="block text-sm">
              {{ t('auth.password') }}
              <span class="relative mt-1 block">
                <input
                  v-model="password"
                  :type="showPassword ? 'text' : 'password'"
                  required
                  :minlength="mode === 'register' ? 8 : undefined"
                  :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
                  :aria-invalid="passwordShort || undefined"
                  placeholder="••••••••"
                  class="input pr-16!"
                />
                <button
                  type="button"
                  class="absolute inset-y-0 right-2 my-auto rounded-lg px-2 text-xs text-muted"
                  :aria-label="t('auth.togglePassword')"
                  @click="showPassword = !showPassword"
                >
                  {{ showPassword ? t('auth.hide') : t('auth.show') }}
                </button>
              </span>
              <span v-if="passwordShort" class="mt-1 block text-xs text-red-600 dark:text-red-400">
                {{ t('auth.passwordShort') }}
              </span>
            </label>

            <template v-if="mode === 'register'">
              <label class="block text-sm">
                {{ t('auth.passwordConfirm') }}
                <input
                  v-model="passwordConfirm"
                  :type="showPassword ? 'text' : 'password'"
                  required
                  autocomplete="new-password"
                  :aria-invalid="passwordMismatch || undefined"
                  class="input mt-1"
                />
                <span v-if="passwordMismatch" class="mt-1 block text-xs text-red-600 dark:text-red-400">
                  {{ t('auth.passwordMismatch') }}
                </span>
              </label>

              <label class="block text-sm">
                {{ t('account.displayName') }}
                <input
                  v-model="displayName"
                  autocomplete="nickname"
                  :placeholder="t('auth.displayNamePlaceholder')"
                  class="input mt-1"
                />
              </label>
            </template>

            <button type="submit" class="btn btn-primary h-11 w-full" :disabled="busy || formInvalid">
              {{ busy ? t('common.loading') : mode === 'signin' ? t('nav.signIn') : t('auth.register') }}
            </button>
          </form>

          <p v-if="error" class="alert alert-error mt-4" role="alert">
            {{ error }}
          </p>
          <p v-else-if="notice" class="alert alert-info mt-4" role="status">
            {{ notice }}
          </p>

          <details v-if="mode === 'signin' && showDemoAccounts" class="mt-5 text-sm">
            <summary class="cursor-pointer select-none text-muted hover:text-accent dark:text-slate-400">
              {{ t('auth.demoAccounts') }}
            </summary>
            <ul class="mt-2 space-y-1">
              <li v-for="demo in demoAccounts" :key="demo.email">
                <button
                  type="button"
                  class="flex w-full items-center justify-between rounded-lg border border-line px-3 py-2 text-left text-xs hover:bg-surface-muted dark:border-slate-800"
                  @click="useDemoAccount(demo)"
                >
                  <code>{{ demo.email }}</code>
                  <span class="text-muted">{{ demo.label }}</span>
                </button>
              </li>
            </ul>
            <p class="mt-2 text-xs text-muted">{{ t('auth.demoHint') }}</p>
          </details>

          <p class="mt-4 text-center text-sm">
            <template v-if="mode === 'signin'">
              {{ t('auth.noAccount') }}
              <button class="font-semibold text-accent" @click="switchMode('register')">
                {{ t('auth.register') }}
              </button>
            </template>
            <template v-else>
              {{ t('auth.haveAccount') }}
              <button class="font-semibold text-accent" @click="switchMode('signin')">
                {{ t('nav.signIn') }}
              </button>
            </template>
          </p>
        </MagicCard>
      </div>
    </div>
  </div>
</template>
