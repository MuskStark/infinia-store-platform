<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { redirectToMonitor, resolveMonitorUrl } from '../status/monitorHandoff';

/**
 * The status page now lives on the standalone monitor (ADR-011) — the page
 * that must stay reachable when this app is not. /status keeps working as a
 * deep link by handing the browser to the monitor's public address.
 */
const { t } = useI18n();
const unconfigured = ref(false);

onMounted(async () => {
  const target = await resolveMonitorUrl();
  if (target !== null) {
    redirectToMonitor(target);
  } else {
    // Degrade honestly instead of dumping raw /api/v1/status JSON on users.
    unconfigured.value = true;
  }
});
</script>

<template>
  <div
    v-if="unconfigured"
    class="mx-auto grid max-w-2xl place-items-center gap-4 py-24 text-center"
    data-testid="status-unconfigured"
  >
    <p class="text-lg font-semibold">{{ t('statusRedirect.unconfiguredTitle') }}</p>
    <p class="text-sm text-muted">{{ t('statusRedirect.unconfiguredBody') }}</p>
    <RouterLink class="btn btn-primary px-5" :to="{ name: 'discover' }">
      {{ t('common.backHome') }}
    </RouterLink>
  </div>
  <div v-else class="mx-auto max-w-5xl py-16 text-center text-sm text-muted">
    {{ t('statusRedirect.redirecting') }}
  </div>
</template>
