<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, formatFen, type MembershipPlan, type MembershipStatus } from '../api/client';
import { Badge } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import ErrorState from '../components/ErrorState.vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import PageHeader from '../components/PageHeader.vue';
import { formatDate } from '../utils/format';

/**
 * Infinia Level purchase (会员等级购买): the caller's ladder position, the
 * purchasable tiers (one card per plan; several plans may share a level with
 * different durations) and the payable channels. Buying creates an order and
 * leaves the SPA for the gateway cashier; the return page polls the result.
 */
const { t } = useI18n();

const status = ref<MembershipStatus | null>(null);
const plans = ref<MembershipPlan[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const buyError = ref<string | null>(null);
const buyingPlanId = ref<string | null>(null);
const channel = ref<string | null>(null);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [s, p] = await Promise.all([api.getMembershipStatus(), api.getMembershipPlans()]);
    status.value = s;
    plans.value = p;
    if (!channel.value && s.channels?.length) {
      channel.value = s.channels[0];
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);

/** The ladder only climbs: a plan is buyable when it beats the effective level or renews the active tier. */
function purchasable(plan: MembershipPlan): boolean {
  const s = status.value;
  if (!s) return false;
  const effective = s.effectiveBeeLevel ?? 0;
  const active = s.membershipLevel != null;
  const renewal = active && s.membershipLevel === plan.beeLevel && s.baseBeeLevel < plan.beeLevel;
  return plan.beeLevel > effective || renewal;
}

function planActionLabel(plan: MembershipPlan): string {
  const s = status.value;
  const renewal = s?.membershipLevel === plan.beeLevel && s && s.baseBeeLevel < plan.beeLevel;
  return renewal ? t('membership.renew') : t('membership.buy');
}

async function buy(plan: MembershipPlan) {
  buyError.value = null;
  buyingPlanId.value = plan.planId;
  try {
    const order = await api.createMembershipOrder({
      planId: plan.planId,
      channel: channel.value ?? undefined,
    });
    if (order.payUrl) {
      window.location.href = order.payUrl;
    } else {
      buyError.value = t('membership.errors.noPayUrl');
    }
  } catch (e) {
    buyError.value = e instanceof Error ? e.message : String(e);
  } finally {
    buyingPlanId.value = null;
  }
}

const channelLabel = computed(
  () => (c: string) => t(`membership.channel.${c}`, c),
);

const paymentDisabled = computed(() => !status.value?.channels?.length);
</script>

<template>
  <div class="space-y-10">
    <PageHeader :title="t('membership.title')">
      <p class="max-w-2xl text-sm text-muted dark:text-slate-400">
        {{ t('membership.subtitle') }}
      </p>
    </PageHeader>

    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else-if="status">
      <!-- Current position: base level + the purchased membership riding on it. -->
      <section class="card flex flex-wrap items-center justify-between gap-4 p-4" data-testid="membership-status">
        <div class="flex items-center gap-3">
          <BeeLevelBadge :level="status.effectiveBeeLevel ?? 0" />
          <div class="text-sm">
            <p v-if="status.membershipLevel != null">
              {{ t('membership.activeMembership', { level: status.membershipLevel }) }}
              · {{ t('membership.expires') }}
              {{ formatDate(status.membershipExpiresAt ?? '') }}
            </p>
            <p v-else-if="(status.effectiveBeeLevel ?? 0) > status.baseBeeLevel">
              {{ t('membership.permanentLevel') }}
            </p>
            <p v-else class="text-muted dark:text-slate-400">
              {{ t('membership.baseLevelHint') }}
            </p>
          </div>
        </div>
        <Badge v-if="paymentDisabled" tone="gold" data-testid="membership-unconfigured">
          {{ t('membership.errors.notConfigured') }}
        </Badge>
      </section>

      <!-- Channel picker: only the configured gateway apps appear. -->
      <section v-if="!paymentDisabled && (status.channels?.length ?? 0) > 1" class="flex items-center gap-2 text-sm">
        <span class="text-muted dark:text-slate-400">{{ t('membership.payWith') }}</span>
        <div class="flex gap-1 rounded-xl border border-line p-1 dark:border-slate-700" role="group">
          <button
            v-for="c in status.channels"
            :key="c"
            type="button"
            class="rounded-lg px-3 py-1.5 transition-colors"
            :class="channel === c
              ? 'bg-accent/10 font-semibold text-accent'
              : 'text-muted hover:bg-surface-muted'"
            :aria-pressed="channel === c"
            @click="channel = c"
          >
            {{ channelLabel(c) }}
          </button>
        </div>
      </section>

      <p v-if="buyError" class="alert alert-error" role="alert" data-testid="membership-buy-error">
        {{ buyError }}
      </p>

      <section aria-labelledby="plans-heading">
        <h2 id="plans-heading" class="mb-3 text-lg font-semibold">{{ t('membership.plans') }}</h2>
        <EmptyState v-if="!plans.length" :title="t('membership.noPlans')" />
        <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="plan in plans"
            :key="plan.planId"
            class="card flex flex-col gap-3 p-5"
            :class="{ 'opacity-60': !purchasable(plan) }"
            data-testid="membership-plan"
          >
            <div class="flex items-center justify-between">
              <BeeLevelBadge :level="plan.beeLevel" />
              <Badge tone="muted">{{ t('membership.days', { n: plan.durationDays }) }}</Badge>
            </div>
            <p class="text-3xl font-bold">{{ formatFen(plan.priceFen ?? 0) }}</p>
            <p class="text-xs text-muted dark:text-slate-400">
              {{ t('membership.planHint', { n: plan.durationDays }) }}
            </p>
            <button
              type="button"
              class="btn btn-primary mt-auto"
              :disabled="!purchasable(plan) || paymentDisabled || buyingPlanId === plan.planId"
              :title="!purchasable(plan) ? t('membership.errors.tooLow') : undefined"
              data-testid="membership-buy"
              @click="buy(plan)"
            >
              {{
                buyingPlanId === plan.planId
                  ? t('membership.creating')
                  : purchasable(plan)
                    ? planActionLabel(plan)
                    : t('membership.owned')
              }}
            </button>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
