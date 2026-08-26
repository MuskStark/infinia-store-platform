<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { completeLogin, useAuthStore } from '../stores/auth';
import { ProgressBar } from '@infinia/magic-ui-vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const failed = ref(false);

onMounted(async () => {
  const code = route.query.code as string | undefined;
  const state = route.query.state as string | undefined;
  if (code && state) {
    const ok = await completeLogin(code, state);
    if (ok) {
      await auth.load();
      const redirect = sessionStorage.getItem('infinia.store.redirect') ?? '/';
      sessionStorage.removeItem('infinia.store.redirect');
      router.replace(redirect);
      return;
    }
  }
  failed.value = true;
});
</script>

<template>
  <div class="mx-auto max-w-md space-y-4 py-20 text-center">
    <template v-if="!failed">
      <p>{{ t('auth.callbackWorking') }}</p>
      <ProgressBar />
    </template>
    <p v-else class="text-red-600 dark:text-red-400">{{ t('auth.callbackFailed') }}</p>
  </div>
</template>
