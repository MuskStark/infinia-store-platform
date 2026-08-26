<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { api } from '../api/client';
import { beginLogin } from '../stores/auth';
import { MagicCard, ShimmerButton } from '@infinia/magic-ui-vue';

const { t } = useI18n();
const router = useRouter();
const route = useRoute();

const mode = ref<'signin' | 'register'>('signin');
const email = ref('');
const const_password = ref('');
const displayName = ref('');
const message = ref('');
const busy = ref(false);
const password = const_password;

async function register() {
  busy.value = true;
  message.value = '';
  try {
    await api.post('/api/v1/auth/register', {
      email: email.value,
      password: password.value,
      displayName: displayName.value || undefined,
    });
    message.value = t('auth.registerSuccess');
    mode.value = 'signin';
  } catch (e) {
    message.value = e instanceof Error ? e.message : 'error';
  } finally {
    busy.value = false;
  }
}

function signIn() {
  sessionStorage.setItem(
    'infinia.store.redirect',
    (route.query.redirect as string) ?? '/',
  );
  beginLogin().then(() => router.push({ name: 'discover' }));
}
</script>

<template>
  <div class="mx-auto max-w-md space-y-6 py-10">
    <MagicCard class="p-8">
      <h1 class="text-xl font-bold">
        {{ mode === 'signin' ? t('auth.signInTitle') : t('auth.registerTitle') }}
      </h1>
      <p class="mt-1 text-sm text-muted dark:text-slate-400">
        {{ mode === 'signin' ? t('auth.signInHint') : '' }}
      </p>

      <div v-if="mode === 'signin'" class="mt-6">
        <ShimmerButton class="w-full" @click="signIn">
          {{ t('nav.signIn') }}
        </ShimmerButton>
        <p class="mt-4 text-center text-sm">
          {{ t('auth.noAccount') }}
          <button class="font-semibold text-accent" @click="mode = 'register'">
            {{ t('auth.register') }}
          </button>
        </p>
      </div>

      <form v-else class="mt-6 space-y-3" @submit.prevent="register">
        <input v-model="email" type="email" required :placeholder="t('auth.email')" class="w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900" />
        <input v-model="password" type="password" required minlength="8" :placeholder="t('auth.password')" class="w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900" />
        <input v-model="displayName" :placeholder="t('account.displayName')" class="w-full rounded-xl border border-line px-4 py-3 dark:border-slate-800 dark:bg-slate-900" />
        <ShimmerButton type="submit" class="w-full" :disabled="busy">
          {{ t('auth.register') }}
        </ShimmerButton>
        <p class="text-center text-sm">
          {{ t('auth.haveAccount') }}
          <button class="font-semibold text-accent" type="button" @click="mode = 'signin'">
            {{ t('nav.signIn') }}
          </button>
        </p>
      </form>

      <p v-if="message" class="mt-4 rounded-xl bg-accent/10 p-3 text-sm text-accent" role="status">
        {{ message }}
      </p>
    </MagicCard>
  </div>
</template>
