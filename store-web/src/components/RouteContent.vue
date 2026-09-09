<script setup lang="ts">
import { onBeforeUnmount, onErrorCaptured, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import ErrorState from './ErrorState.vue';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const failed = ref(false);
const loading = ref(route.matched.length === 0);
let retryPath = window.location.pathname + window.location.search + window.location.hash;
const removeBefore = router.beforeEach(() => { loading.value = true; });
const removeAfter = router.afterEach((_to, _from, failure) => {
  loading.value = false;
  if (!failure) failed.value = false;
});
const removeError = router.onError((_error, to) => {
  retryPath = to.fullPath;
  loading.value = false;
  failed.value = true;
});
onErrorCaptured(() => {
  retryPath = route.fullPath;
  loading.value = false;
  failed.value = true;
  return false;
});
onBeforeUnmount(() => { removeBefore(); removeAfter(); removeError(); });

// A full reload also replaces an outdated entry bundle after a deployment.
function retry() { window.location.assign(retryPath); }
</script>

<template>
  <ErrorState v-if="failed" :message="t('common.pageLoadError')" @retry="retry" />
  <template v-else>
    <p v-if="loading" role="status" class="py-8 text-center text-muted">{{ t('common.loading') }}</p>
    <RouterView :key="route.fullPath" />
  </template>
</template>
