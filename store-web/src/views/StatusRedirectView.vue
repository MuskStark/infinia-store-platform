<script setup lang="ts">
/**
 * The status page now lives on the standalone monitor (ADR-011) — the page
 * that must stay reachable when this app is not. /status keeps working as a
 * deep link by handing the browser to the monitor's public address.
 */
const monitorBase = import.meta.env.VITE_MONITOR_BASE_URL as string | undefined;

if (monitorBase) {
  window.location.replace(monitorBase.replace(/\/+$/, ''));
} else {
  // Unconfigured deployments degrade honestly instead of a redirect loop.
  window.location.replace('/api/v1/status');
}
</script>

<template>
  <div class="mx-auto max-w-5xl py-16 text-center text-sm text-muted">
    Redirecting to the service status page…
  </div>
</template>
