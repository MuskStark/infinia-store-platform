<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { api, formatFen, type MembershipOrder } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { Badge } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import PageHeader from '../components/PageHeader.vue';

/**
 * Where the gateway's return_url lands after the cashier (支付回跳): the
 * browser comes back before the async callback may have arrived, so this page
 * polls the order until it flips to PAID (and refreshes /me so the header
 * badge updates), CLOSED, or the polling budget runs out.
 */
const POLL_INTERVAL_MS = 2000;
const MAX_POLLS = 60;

const { t } = useI18n();
const route = useRoute();
const auth = useAuthStore();

const orderNo = computed(() => String(route.query.orderNo ?? ''));
const order = ref<MembershipOrder | null>(null);
const state = ref<'polling' | 'paid' | 'closed' | 'missing' | 'error'>('polling');
let polls = 0;
let timer: number | undefined;

async function poll() {
  polls += 1;
  try {
    order.value = await api.getMembershipOrder(orderNo.value);
    if (order.value.status === 'PAID') {
      state.value = 'paid';
      // The effective level lives on the account row — refresh the badge.
      await auth.load();
      stop();
      return;
    }
    if (order.value.status === 'CLOSED') {
      state.value = 'closed';
      stop();
      return;
    }
  } catch {
    state.value = 'error';
    stop();
    return;
  }
  if (polls >= MAX_POLLS) {
    stop();
  }
}

function stop() {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

onMounted(() => {
  if (!orderNo.value) {
    state.value = 'missing';
    return;
  }
  void poll();
  timer = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
});
onBeforeUnmount(stop);
</script>

<template>
  <div class="mx-auto max-w-xl space-y-8 py-10">
    <PageHeader :title="t('membership.result.title')" />

    <!-- Polling: the callback usually lands within a couple of seconds. -->
    <div
      v-if="state === 'polling'"
      class="card flex flex-col items-center gap-3 p-8 text-center"
      data-testid="membership-result-polling"
    >
      <span class="size-8 animate-spin rounded-full border-2 border-line border-t-accent dark:border-slate-700" />
      <p class="font-medium">{{ t('membership.result.waiting') }}</p>
      <p v-if="order" class="text-sm text-muted dark:text-slate-400">
        {{ t('membership.result.order') }}
        <code class="text-xs">{{ order.orderNo }}</code>
        · {{ formatFen(order.priceFen ?? 0) }}
      </p>
    </div>

    <div
      v-else-if="state === 'paid' && order"
      class="card flex flex-col items-center gap-4 p-8 text-center"
      data-testid="membership-result-paid"
    >
      <span class="text-4xl" aria-hidden="true">🐝</span>
      <p class="text-lg font-semibold">{{ t('membership.result.success') }}</p>
      <BeeLevelBadge :level="order.targetLevel ?? 0" />
      <p class="text-sm text-muted dark:text-slate-400">
        {{ t('membership.result.paidFor', { n: order.durationDays ?? 0 }) }}
      </p>
      <RouterLink to="/library" class="btn btn-primary">{{ t('membership.result.browse') }}</RouterLink>
    </div>

    <div
      v-else-if="state === 'closed'"
      class="card flex flex-col items-center gap-3 p-8 text-center"
      data-testid="membership-result-closed"
    >
      <Badge tone="gold">{{ t('membership.result.closed') }}</Badge>
      <p class="text-sm text-muted dark:text-slate-400">{{ t('membership.result.closedHint') }}</p>
      <RouterLink to="/membership" class="btn btn-primary">{{ t('membership.result.reorder') }}</RouterLink>
    </div>

    <div v-else class="card flex flex-col items-center gap-3 p-8 text-center">
      <Badge tone="danger">{{ t('membership.result.error') }}</Badge>
      <p class="text-sm text-muted dark:text-slate-400">{{ t('membership.result.errorHint') }}</p>
      <RouterLink to="/membership" class="btn btn-primary">{{ t('membership.result.reorder') }}</RouterLink>
    </div>
  </div>
</template>
