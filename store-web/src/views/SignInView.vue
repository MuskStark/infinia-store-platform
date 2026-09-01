<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { api, ApiRequestError, setAccessToken, type PublicUser } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { submitOAuthSessionLogin } from '../auth/sessionLogin';
import { MagicCard, ShimmerButton } from '@infinia/magic-ui-vue';

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

/** Seeded demo accounts (local/test profiles) — one click fills the form. */
const demoAccounts = computed(() => [
  { email: 'admin@infinia.local', label: t('role.PLATFORM_ADMIN') },
  { email: 'reviewer@infinia.local', label: t('role.REVIEWER') },
  { email: 'publisher@infinia.local', label: t('role.PUBLISHER') },
  { email: 'user@infinia.local', label: t('role.USER') },
]);

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

function fillDemo(demoEmail: string) {
  mode.value = 'signin';
  error.value = null;
  notice.value = null;
  email.value = demoEmail;
  password.value = 'Password123!';
}

function problemText(e: unknown): string {
  if (e instanceof ApiRequestError && e.code) {
    const localized = t(`errors.${e.code}`);
    if (localized !== `errors.${e.code}`) return localized;
    return e.detail ?? e.message;
  }
  return t('errors.server');
}

async function finishLogin(token: string) {
  setAccessToken(token);
  await auth.load();
  const redirect = (route.query.redirect as string) ?? '/';
  router.push(redirect);
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
    const response = await api.post<{ accessToken: string; user?: PublicUser }>(
      '/api/v1/auth/login',
      { email: email.value, password: password.value },
    );
    await finishLogin(response.accessToken);
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
    const response = await api.post<{ accessToken: string }>('/api/v1/auth/login', {
      email: email.value,
      password: password.value,
    });
    await finishLogin(response.accessToken);
  } catch (e) {
    error.value = problemText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-md py-10">
    <MagicCard class="p-8">
      <div class="mb-6 flex items-center gap-3">
        <span class="grid h-10 w-10 place-items-center rounded-xl bg-gradient-to-br from-accent to-accent2 text-lg font-bold text-white">∞</span>
        <h1 class="text-xl font-bold">
          {{ mode === 'signin' ? t('auth.signInTitle') : t('auth.registerTitle') }}
        </h1>
      </div>

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
            class="mt-1 w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900"
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
              placeholder="Password123!"
              class="w-full rounded-xl border border-line px-4 py-3 pr-16 dark:border-slate-800 dark:bg-slate-900"
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
              class="mt-1 w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900"
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
              class="mt-1 w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900"
            />
          </label>
        </template>

        <ShimmerButton type="submit" class="w-full" :disabled="busy || formInvalid">
          {{ busy ? t('common.loading') : mode === 'signin' ? t('nav.signIn') : t('auth.register') }}
        </ShimmerButton>
      </form>

      <p
        v-if="error"
        class="mt-4 rounded-xl border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300"
        role="alert"
      >
        {{ error }}
      </p>
      <p v-else-if="notice" class="mt-4 rounded-xl bg-accent/10 p-3 text-sm text-accent" role="status">
        {{ notice }}
      </p>

      <details v-if="mode === 'signin'" class="mt-5 text-sm">
        <summary class="cursor-pointer select-none text-muted hover:text-accent dark:text-slate-400">
          {{ t('auth.demoAccounts') }}
        </summary>
        <ul class="mt-2 space-y-1">
          <li v-for="demo in demoAccounts" :key="demo.email">
            <button
              type="button"
              class="flex w-full items-center justify-between rounded-lg border border-line px-3 py-2 text-left text-xs hover:bg-surface-muted dark:border-slate-800"
              @click="fillDemo(demo.email)"
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
</template>
