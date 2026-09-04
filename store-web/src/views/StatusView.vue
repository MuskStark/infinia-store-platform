<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  api,
  type ServiceIncident,
  type ServiceStatus,
  type StatusDayUptime,
  type StatusIndicator,
} from '../api/client';
import { formatDate, formatDateTime } from '../utils/format';
import ErrorState from '../components/ErrorState.vue';

const { t } = useI18n();
const status = ref<ServiceStatus | null>(null);
const incidents = ref<ServiceIncident[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const lastUpdated = ref<number | null>(null);
const nowTick = ref(Date.now());

let refreshTimer: ReturnType<typeof setInterval> | undefined;
let tickTimer: ReturnType<typeof setInterval> | undefined;

async function load() {
  try {
    // The incident feed failing alone should not blank the whole page.
    const [page, incidentList] = await Promise.all([
      api.getServiceStatus(),
      api.getServiceIncidents().catch(() => [] as ServiceIncident[]),
    ]);
    status.value = page;
    incidents.value = incidentList;
    error.value = null;
    lastUpdated.value = Date.now();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  // Status pages exist so people can glance at them; refresh on a cadence.
  refreshTimer = setInterval(load, 60_000);
  tickTimer = setInterval(() => (nowTick.value = Date.now()), 1_000);
});

onBeforeUnmount(() => {
  clearInterval(refreshTimer);
  clearInterval(tickTimer);
});

const indicatorKeys: Record<StatusIndicator, string> = {
  operational: 'status.indicator.operational',
  degraded: 'status.indicator.degraded',
  partial_outage: 'status.indicator.partialOutage',
  major_outage: 'status.indicator.majorOutage',
  no_data: 'status.indicator.noData',
};

function indicatorText(indicator: string): string {
  return t(indicatorKeys[indicator as StatusIndicator] ?? 'status.indicator.noData');
}

/** Bar/label colors per indicator; gray means "no samples that day". */
function indicatorColor(indicator: string): string {
  switch (indicator) {
    case 'operational':
      return 'bg-emerald-500';
    case 'degraded':
      return 'bg-amber-400';
    case 'partial_outage':
      return 'bg-orange-500';
    case 'major_outage':
      return 'bg-red-500';
    default:
      return 'bg-slate-200 dark:bg-slate-700';
  }
}

const banners: Record<string, { key: string; bar: string }> = {
  operational: { key: 'status.banner.operational', bar: 'bg-emerald-500' },
  degraded: { key: 'status.banner.degraded', bar: 'bg-amber-400' },
  partial_outage: { key: 'status.banner.partialOutage', bar: 'bg-orange-500' },
  major_outage: { key: 'status.banner.majorOutage', bar: 'bg-red-500' },
};
const banner = computed(
  () => banners[status.value?.indicator ?? 'operational'] ?? banners.operational,
);

function componentText(key: string): string {
  return t(`status.component.${key}`, key);
}

const updatedAgo = computed(() => {
  if (!lastUpdated.value) return null;
  const seconds = Math.max(0, Math.round((nowTick.value - lastUpdated.value) / 1000));
  if (seconds < 5) return t('status.updatedJustNow');
  return t('status.updatedSecondsAgo', { n: seconds });
});

/** One shared tooltip per strip — 90 hover targets must not mean 90 popovers. */
const tooltip = ref<{ day: StatusDayUptime; left: number } | null>(null);

function showTooltip(day: StatusDayUptime, event: MouseEvent) {
  const bar = event.currentTarget as HTMLElement;
  tooltip.value = { day, left: bar.offsetLeft + bar.offsetWidth / 2 };
}

function tooltipText(day: StatusDayUptime): string {
  return day.uptimePercent == null
    ? `${day.date} · ${indicatorText(day.indicator)}`
    : `${day.date} · ${t('status.barUptime', { percent: day.uptimePercent.toFixed(2) })}`;
}

/** Incidents grouped by UTC start date, newest first (statuspage style). */
const incidentGroups = computed(() => {
  const groups = new Map<string, ServiceIncident[]>();
  for (const incident of incidents.value) {
    const day = incident.startedAt.slice(0, 10);
    if (!groups.has(day)) groups.set(day, []);
    groups.get(day)!.push(incident);
  }
  return [...groups.entries()];
});

function incidentDuration(incident: ServiceIncident): string | null {
  if (!incident.resolvedAt) return null;
  const ms = new Date(incident.resolvedAt).getTime() - new Date(incident.startedAt).getTime();
  const minutes = Math.max(1, Math.round(ms / 60_000));
  if (minutes < 60) return t('status.durationMinutes', { n: minutes });
  const hours = Math.floor(minutes / 60);
  return t('status.durationHours', { n: hours + (minutes % 60 >= 30 ? 0.5 : 0) });
}
</script>

<template>
  <div class="mx-auto max-w-4xl space-y-8">
    <header class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-3xl font-extrabold tracking-tight">{{ t('status.title') }}</h1>
        <p class="mt-1 text-sm text-muted dark:text-slate-400">{{ t('status.subtitle') }}</p>
      </div>
      <div class="flex items-center gap-3 text-xs text-muted dark:text-slate-400">
        <span v-if="updatedAgo" data-testid="status-updated">{{ updatedAgo }}</span>
        <button
          class="rounded-xl border border-line px-3 py-1.5 text-sm font-medium hover:bg-surface-muted dark:border-slate-800"
          :disabled="loading"
          @click="load"
        >
          {{ t('status.refresh') }}
        </button>
      </div>
    </header>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <template v-else-if="status">
      <!-- Overall banner, mirrors the npm status page's green strip. -->
      <section
        class="flex items-center gap-3 rounded-2xl px-5 py-4 text-base font-semibold text-white"
        :class="banner.bar"
        data-testid="status-banner"
        role="status"
      >
        <span aria-hidden="true">
          {{ status.indicator === 'operational' ? '✓' : '!' }}
        </span>
        {{ t(banner.key) }}
      </section>

      <!-- Per-component uptime: 90 bars per component like statuspage. -->
      <section class="rounded-2xl border border-line bg-surface dark:border-slate-800 dark:bg-slate-900">
        <div class="flex items-center justify-between border-b border-line px-5 py-4 dark:border-slate-800">
          <h2 class="font-semibold">{{ t('status.components') }}</h2>
          <span class="text-xs text-muted dark:text-slate-400">{{ t('status.historyNote') }}</span>
        </div>
        <div
          v-for="component in status.components"
          :key="component.key"
          class="border-b border-line px-5 py-4 last:border-b-0 dark:border-slate-800"
          data-testid="status-component"
        >
          <div class="flex items-baseline justify-between gap-4">
            <h3 class="font-medium">{{ componentText(component.key) }}</h3>
            <div class="flex items-baseline gap-3 text-right">
              <span
                v-if="component.uptime90d != null"
                class="text-xs text-muted dark:text-slate-400"
              >
                {{ t('status.uptime90d', { percent: component.uptime90d.toFixed(2) }) }}
              </span>
              <span
                class="inline-flex items-center gap-1.5 text-sm"
                :class="component.indicator === 'operational' ? '' : 'text-muted dark:text-slate-300'"
              >
                <span
                  class="h-2 w-2 rounded-full"
                  :class="indicatorColor(component.indicator)"
                  aria-hidden="true"
                />
                {{ indicatorText(component.indicator) }}
              </span>
            </div>
          </div>

          <div class="relative mt-3">
            <!-- Non-interactive bars (a <button> would inherit the global 44px
                 touch-target rule and stretch the strip). -->
            <div
              class="flex h-7 items-stretch gap-px"
              role="img"
              :aria-label="t('status.historyNote')"
              @mouseleave="tooltip = null"
            >
              <span
                v-for="day in component.history"
                :key="day.date"
                class="flex-1 rounded-[2px] transition-transform hover:scale-y-125"
                :class="indicatorColor(day.indicator)"
                @mouseenter="showTooltip(day, $event)"
              />
            </div>
            <div
              v-if="tooltip"
              class="pointer-events-none absolute bottom-full z-10 -translate-x-1/2 whitespace-nowrap rounded-lg bg-ink px-2.5 py-1.5 text-xs text-surface shadow-lg"
              :style="{ left: `${tooltip.left}px` }"
            >
              {{ tooltipText(tooltip.day) }}
            </div>
          </div>
        </div>
      </section>

      <!-- Past incidents, grouped by day. -->
      <section data-testid="status-incidents">
        <h2 class="mb-3 text-lg font-semibold">{{ t('status.pastIncidents') }}</h2>
        <div
          v-if="incidentGroups.length === 0"
          class="rounded-2xl border border-line bg-surface px-5 py-8 text-center text-sm text-muted dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400"
        >
          {{ t('status.noIncidents') }}
        </div>
        <div
          v-for="[day, dayIncidents] in incidentGroups"
          :key="day"
          class="mb-4 rounded-2xl border border-line bg-surface dark:border-slate-800 dark:bg-slate-900"
        >
          <div class="border-b border-line px-5 py-3 text-sm font-medium dark:border-slate-800">
            {{ formatDate(day) }}
          </div>
          <div
            v-for="incident in dayIncidents"
            :key="incident.incidentId"
            class="border-b border-line px-5 py-4 last:border-b-0 dark:border-slate-800"
          >
            <div class="flex flex-wrap items-center gap-2">
              <span
                class="rounded-full px-2 py-0.5 text-xs font-semibold text-white"
                :class="incident.status === 'resolved' ? 'bg-emerald-500' : 'bg-red-500'"
              >
                {{ incident.status === 'resolved' ? t('status.incidentResolved') : t('status.incidentInvestigating') }}
              </span>
              <span class="font-medium">{{ incident.title }}</span>
              <span class="text-xs text-muted dark:text-slate-400">
                · {{ componentText(incident.component) }}
              </span>
            </div>
            <p class="mt-1 text-xs text-muted dark:text-slate-400">
              {{ formatDateTime(incident.startedAt) }}
              <template v-if="incidentDuration(incident)">
                · {{ incidentDuration(incident) }}
              </template>
            </p>
          </div>
        </div>
      </section>

      <p class="pb-2 text-center text-xs text-muted dark:text-slate-500">
        {{ t('status.autoRefresh') }}
      </p>
    </template>

    <template v-else-if="loading">
      <div class="h-14 animate-pulse rounded-2xl bg-surface-muted dark:bg-slate-800" />
      <div class="h-64 animate-pulse rounded-2xl bg-surface-muted dark:bg-slate-800" />
    </template>
  </div>
</template>
