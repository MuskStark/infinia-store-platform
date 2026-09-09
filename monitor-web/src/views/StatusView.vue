<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type ServiceIncident, type ServiceStatus, type StatusDayUptime, type StatusIndicator } from '../api/client';
import { formatDate, formatDateTime } from '../utils/format';
import ErrorState from '../components/ErrorState.vue';
import PageHeader from '../components/PageHeader.vue';

const { t, te } = useI18n();
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
      api.getStatus(),
      api.getIncidents().catch(() => [] as ServiceIncident[]),
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
  window.addEventListener('scroll', hideTooltip, true);
  window.addEventListener('resize', hideTooltip);
  window.addEventListener('resize', measureHiveScale);
});

onBeforeUnmount(() => {
  clearInterval(refreshTimer);
  clearInterval(tickTimer);
  window.removeEventListener('scroll', hideTooltip, true);
  window.removeEventListener('resize', hideTooltip);
  window.removeEventListener('resize', measureHiveScale);
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

/** Day-cell colors per indicator; gray means "no samples that day". */
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
      return 'bg-slate-300 dark:bg-slate-600';
  }
}

/** Hero accent + glyph follow the overall indicator. */
const banner = computed(() => {
  switch (status.value?.indicator) {
    case 'degraded':
      return { key: 'status.banner.degraded', cls: 'text-warning dark:text-amber-400' };
    case 'partial_outage':
      return { key: 'status.banner.partialOutage', cls: 'text-orange-600 dark:text-orange-400' };
    case 'major_outage':
      return { key: 'status.banner.majorOutage', cls: 'text-danger dark:text-red-400' };
    case 'operational':
      return { key: 'status.banner.operational', cls: 'text-success dark:text-emerald-400' };
    default:
      return { key: 'status.indicator.noData', cls: 'text-muted dark:text-slate-400' };
  }
});

/**
 * Frozen-view banner: the monitor survives the store, so the page must say
 * plainly when the numbers below are the last known ones (ADR-011).
 */
const staleBanner = computed(() => {
  if (!status.value?.stale) return null;
  return status.value.mirroredAt
    ? t('status.staleBanner', { time: formatDateTime(status.value.mirroredAt) })
    : t('status.neverReached');
});

/** Honest aggregate: the mean of the components' 90-day uptime. */
const averageUptime = computed(() => {
  const withUptime = (status.value?.components ?? []).filter((c) => c.uptime90d != null);
  if (!withUptime.length) return null;
  const sum = withUptime.reduce((total, c) => total + (c.uptime90d ?? 0), 0);
  return sum / withUptime.length;
});

function componentText(key: string): string {
  return t(`status.component.${key}`, key);
}

/**
 * Backend incident titles are persisted in English ("<Component> is
 * unavailable"); rebuild them from the component key + impact so the timeline
 * follows the page language, falling back to the stored title for unknown
 * components or hand-written titles.
 */
function incidentTitle(incident: ServiceIncident): string {
  const componentKey = `status.component.${incident.component}`;
  if (!te(componentKey)) return incident.title;
  const suffix
    = incident.impact === 'outage'
      ? t('status.incidentUnavailable')
      : t('status.incidentDegraded');
  return t(componentKey) + suffix;
}

const updatedAgo = computed(() => {
  if (!lastUpdated.value) return null;
  const seconds = Math.max(0, Math.round((nowTick.value - lastUpdated.value) / 1000));
  if (seconds < 5) return t('status.updatedJustNow');
  return t('status.updatedSecondsAgo', { n: seconds });
});

/* Edge-sharing pointy-top hexagons; each service owns one border color, mapped
 * to its name in the legend — the hive itself stays label-free. */

const CELL_W = 180;
const CELL_H = 208;
const ROW_STEP = CELL_H * 0.75;
const HIVE_LEFT = 16;
const HIVE_TOP = 16;
/** The hive generalizes to any component count: center + two rings = 19 slots. */
const HIVE_RADIUS = 2;

/** One border color per service, in component order. */
const SERVICE_COLORS = [
  '#0b70f5', // blue
  '#fc801d', // orange
  '#21d789', // green
  '#a73afd', // purple
  '#fe2857', // red-pink
  '#eab308', // yellow
  '#14b8a6', // teal
  '#f472b6', // pink
  '#22d3ee', // cyan
  '#818cf8', // indigo
  '#a3e635', // lime
  '#d946ef', // magenta
  '#c2410c', // burnt orange
  '#64748b', // slate
];

type Axial = { q: number; r: number };

function hexDistance({ q, r }: Axial): number {
  return (Math.abs(q) + Math.abs(r) + Math.abs(q + r)) / 2;
}

function pixelX({ q, r }: Axial): number {
  return (HIVE_RADIUS + q + r / 2) * CELL_W;
}

function pixelY({ r }: Axial): number {
  return (HIVE_RADIUS + r) * ROW_STEP;
}

/** Ring cells in reading order: starting at the top, circling clockwise. */
function ringClockwise(radius: number): Axial[] {
  const cells: Axial[] = [];
  for (let q = -radius; q <= radius; q++) {
    for (let r = -radius; r <= radius; r++) {
      if (hexDistance({ q, r }) === radius) {
        cells.push({ q, r });
      }
    }
  }
  return cells.sort((a, b) => angle(a) - angle(b));
}

function angle(cell: Axial): number {
  const x = pixelX(cell) - pixelX({ q: 0, r: 0 });
  const y = pixelY(cell) - pixelY({ q: 0, r: 0 });
  return Math.atan2(x, -y); // 0 at the top, growing clockwise
}

const hiveSlots: { cell: Axial; overall?: boolean }[] = [
  { cell: { q: 0, r: 0 }, overall: true },
  ...ringClockwise(1).map((cell) => ({ cell })),
  ...ringClockwise(2).map((cell) => ({ cell })),
];

const hive = computed(() => {
  const components = status.value?.components ?? [];
  const cells = hiveSlots.map((slot, index) => {
    const overall = Boolean(slot.overall);
    const serviceIndex = overall ? -1 : index - 1;
    const component = serviceIndex >= 0 && serviceIndex < components.length
      ? components[serviceIndex]
      : undefined;
    return {
      kind: (overall ? 'overall' : component ? 'service' : 'spare') as 'overall' | 'service' | 'spare',
      component,
      color: component ? SERVICE_COLORS[serviceIndex % SERVICE_COLORS.length] : undefined,
      x: pixelX(slot.cell) + HIVE_LEFT,
      y: pixelY(slot.cell) + HIVE_TOP,
      key: `hive-${index}`,
    };
  });
  const columns = HIVE_RADIUS * 2 + 1;
  return {
    cells,
    width: HIVE_LEFT * 2 + columns * CELL_W,
    height: HIVE_TOP * 2 + (HIVE_RADIUS * 2) * ROW_STEP + CELL_H,
  };
});

/* The complete hive always stays on screen: no scroll containers, and it
 * scales proportionally to fit BOTH the width and the height the page gives
 * it (tooltips follow visual coordinates, so they stay accurate under the
 * transform). */
const hiveWrap = ref<HTMLElement | null>(null);
const hiveScale = ref(1);

/** Vertical reserve below the hive: indicator ladder row plus breathing room. */
const BOTTOM_RESERVE_PX = 130;

function measureHiveScale() {
  const wrap = hiveWrap.value;
  if (!wrap || wrap.clientWidth <= 0) {
    hiveScale.value = 1; // jsdom / unmeasured: keep the natural 1:1 geometry
    return;
  }
  const widthScale = wrap.clientWidth / hive.value.width;
  const absoluteTop = wrap.getBoundingClientRect().top + window.scrollY;
  const availableHeight = Math.max(200, window.innerHeight - absoluteTop - BOTTOM_RESERVE_PX);
  hiveScale.value = Math.min(1, widthScale, availableHeight / hive.value.height);
}

watch(status, () => {
  nextTick(measureHiveScale);
});

/** Legend rows: border color → service name, live indicator and 90-day uptime. */
const hiveLegend = computed(() => {
  return (status.value?.components ?? []).map((component, index) => ({
    key: component.key,
    name: componentText(component.key),
    color: SERVICE_COLORS[index % SERVICE_COLORS.length],
    indicator: indicatorText(component.indicator),
    indicatorColor: indicatorColor(component.indicator),
    uptime: component.uptime90d != null
      ? t('status.uptime90d', { percent: component.uptime90d.toFixed(2) })
      : null,
  }));
});

/** A regular hexagonal lattice, contained by the large hexagon's six edges.
 * The quarter-row phase yields exactly 90 complete tiles, with no clipped days.
 * Coordinates use a 200 × 230.94 view box and scale with the service comb. */
const HEX_WIDTH = 200;
const HEX_HEIGHT = HEX_WIDTH * 2 / Math.sqrt(3);
const DAY_WIDTH = 19.7;
const DAY_HEIGHT = DAY_WIDTH * 2 / Math.sqrt(3);
const daySlots: { x: number; y: number }[] = [];
const fillerSlots: { x: number; y: number }[] = [];
const vertices = [[0, -0.5], [0.5, -0.25], [0.5, 0.25], [0, 0.5], [-0.5, 0.25], [-0.5, -0.25]];
for (let row = -8; row <= 7; row++) {
  for (let col = -6; col <= 5; col++) {
    const x = (col + (Math.abs(row) % 2) / 2 + 0.5) * DAY_WIDTH;
    const y = (row + 0.25) * DAY_HEIGHT * 0.75;
    fillerSlots.push({ x: x + HEX_WIDTH / 2, y: y + HEX_HEIGHT / 2 });
    if (vertices.every(([dx, dy]) => {
      const vx = Math.abs(x + dx * DAY_WIDTH);
      const vy = Math.abs(y + dy * DAY_HEIGHT);
      return vx <= HEX_WIDTH / 2 && vx / (HEX_WIDTH / 2) + 2 * vy / (HEX_HEIGHT / 2) <= 2;
    })) {
      daySlots.push({ x: x + HEX_WIDTH / 2, y: y + HEX_HEIGHT / 2 });
    }
  }
}

/** Decorative cells share the exact daily lattice, including cropped edge cells. */
function hexCellStyle(x: number, y: number) {
  return {
    left: `${x / HEX_WIDTH * 100}%`,
    top: `${y / HEX_HEIGHT * 100}%`,
    width: `${DAY_WIDTH * 0.9 / HEX_WIDTH * 100}%`,
    height: `${DAY_HEIGHT * 0.9 / HEX_HEIGHT * 100}%`,
  };
}

function dayCells(history: StatusDayUptime[]) {
  return history.slice(-90).map((day, index) => ({ day, ...daySlots[index] }));
}

/** The day-cell tooltip: which service, which day, which state, how available. */
const tooltip = ref<{
  componentKey: string;
  day: StatusDayUptime;
  x: number;
  y: number;
  below: boolean;
} | null>(null);

/** Clicking a legend entry spotlights that service: its comb keeps its colors
 * while the rest of the hive sinks into a gray overlay. Null = whole hive. */
const selectedKey = ref<string | null>(null);

function toggleSelected(key: string) {
  selectedKey.value = selectedKey.value === key ? null : key;
}

function hideTooltip() {
  tooltip.value = null;
}

function showTooltip(componentKey: string, day: StatusDayUptime, event: Event) {
  const target = event.currentTarget as HTMLElement;
  const rect = target.getBoundingClientRect();
  const halfWidth = Math.min(260, window.innerWidth - 24) / 2;
  const below = rect.top < 100;
  tooltip.value = {
    componentKey,
    day,
    x: Math.max(halfWidth + 12, Math.min(rect.left + rect.width / 2, window.innerWidth - halfWidth - 12)),
    y: below ? rect.bottom + 8 : rect.top - 8,
    below,
  };
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
  <div class="space-y-8">
    <PageHeader :title="t('status.title')" :subtitle="t('status.subtitle')">
      <template #actions>
        <span v-if="updatedAgo" class="text-xs text-muted dark:text-slate-400" data-testid="status-updated">{{ updatedAgo }}</span>
        <button class="btn btn-secondary" :disabled="loading" @click="load">
          {{ t('status.refresh') }}
        </button>
      </template>
    </PageHeader>

    <ErrorState v-if="error" :message="error" @retry="load" />

    <template v-else-if="status">
      <span class="sr-only" data-testid="status-banner" role="status">
        {{ t(banner.key) }}
      </span>

      <!-- Frozen-view banner: the store is unreachable, data below is frozen. -->
      <div
        v-if="staleBanner"
        data-testid="stale-banner"
        role="alert"
        class="rounded-lg border border-warning/40 bg-warning/10 px-4 py-3 text-sm font-medium text-warning dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-400"
      >
        ⚠ {{ staleBanner }}
      </div>

      <section :aria-label="t('status.components')">
        <div class="flex flex-col items-center gap-8 lg:flex-row lg:items-center lg:justify-center">
        <!-- Service legend: one swatch per border color, with live status.
             On narrow screens it follows the hive so the comb sits near the top. -->
        <ul class="hive-legend order-last w-full lg:order-none lg:w-auto" data-testid="hive-legend">
          <li v-for="entry in hiveLegend" :key="entry.key">
            <button
              type="button"
              class="hive-legend__item"
              :class="{ 'hive-legend__item--active': selectedKey === entry.key }"
              :aria-pressed="selectedKey === entry.key"
              @click="toggleSelected(entry.key)"
            >
              <span class="hive-legend__swatch" :style="{ background: entry.color }" aria-hidden="true" />
              <span class="text-sm font-medium">{{ entry.name }}</span>
              <span class="ml-auto inline-flex items-center gap-1.5 whitespace-nowrap text-xs" data-testid="component-live-status">
                <span class="h-2 w-2 rounded-full" :class="entry.indicatorColor" aria-hidden="true" />
                {{ entry.indicator }}
              </span>
            </button>
          </li>
        </ul>
        <div ref="hiveWrap" class="w-full min-w-0" data-testid="hive-fit">
          <div
            :style="{
              width: `${hive.width * hiveScale}px`,
              height: `${hive.height * hiveScale}px`,
              margin: '0 auto',
            }"
          >
            <div
              class="hive"
              :style="{
                width: `${hive.width}px`,
                height: `${hive.height}px`,
                transform: `scale(${hiveScale})`,
                transformOrigin: 'top left',
              }"
              @mouseleave="tooltip = null"
            >
              <article
                v-for="cell in hive.cells"
                :key="cell.key"
                class="hive-cell"
                :class="[
                  `hive-cell--${cell.kind}`,
                  selectedKey && cell.component?.key !== selectedKey ? 'hive-cell--dim' : '',
                  selectedKey && cell.component?.key === selectedKey ? 'hive-cell--focus' : '',
                ]"
                :style="{ '--cell-x': `${cell.x}px`, '--cell-y': `${cell.y}px`, width: `${CELL_W}px`, height: `${CELL_H}px` }"
                :data-testid="cell.kind === 'service' ? 'status-component' : undefined"
              >
                <div
                  class="hive-cell__rim"
                  :class="cell.color ? '' : cell.kind === 'overall' ? indicatorColor(status.indicator) : 'bg-line'"
                  :style="cell.color ? { background: cell.color } : undefined"
                  aria-hidden="true"
                />
                <div class="hive-cell__body">
                  <div class="hive-fill" aria-hidden="true">
                    <span
                      v-for="(slot, index) in fillerSlots"
                      :key="index"
                      class="hive-fill__hex bg-slate-200 dark:bg-slate-700"
                      :style="hexCellStyle(slot.x, slot.y)"
                    />
                  </div>
                  <template v-if="cell.component">
                    <div class="hive-history" :aria-label="t('status.historyNote')">
                      <button
                        v-for="{ day, x, y } in dayCells(cell.component.history)"
                        :key="day.date"
                        type="button"
                        class="hive-day"
                        :class="indicatorColor(day.indicator)"
                        :style="hexCellStyle(x, y)"
                        :aria-label="tooltipText(day)"
                        @mouseenter="showTooltip(cell.component.key, day, $event)"
                        @mouseleave="tooltip = null"
                        @focus="showTooltip(cell.component.key, day, $event)"
                        @blur="tooltip = null"
                        @click="showTooltip(cell.component.key, day, $event)"
                        @keydown.esc="tooltip = null"
                      />
                    </div>
                  </template>
                  <div v-else-if="cell.kind === 'overall'" class="hive-overall">
                    <h2 class="text-sm font-bold">{{ t('status.overall') }}</h2>
                    <span class="text-2xl font-bold tabular-nums" :class="banner.cls">
                      {{ averageUptime != null ? `${averageUptime.toFixed(2)}%` : '—' }}
                    </span>
                    <span class="text-[10px] text-muted">{{ t('status.averageUptime') }}</span>
                    <span class="text-[11px] font-semibold" :class="banner.cls">{{ indicatorText(status.indicator) }}</span>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </div>
        </div>

        <div class="mt-5 flex flex-wrap items-center justify-center gap-x-4 gap-y-2 text-xs text-muted">
          <span v-for="indicator in ['operational', 'degraded', 'partial_outage', 'major_outage', 'no_data'] as const" :key="indicator" class="inline-flex items-center gap-1.5">
            <span class="hive-legend-day inline-block h-3 w-2.5" :class="indicatorColor(indicator)" aria-hidden="true" />
            {{ indicatorText(indicator) }}
          </span>
        </div>
      </section>

      <!-- ═══ Past incidents as a dated timeline rail. ═══ -->
      <section data-testid="status-incidents">
        <h2 class="mb-3 text-lg font-bold">{{ t('status.pastIncidents') }}</h2>
        <div
          v-if="incidentGroups.length === 0"
          class="card px-5 py-8 text-center text-sm text-muted dark:text-slate-400"
        >
          {{ t('status.noIncidents') }}
        </div>
        <div v-for="[day, dayIncidents] in incidentGroups" :key="day" class="mb-4">
          <div class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted dark:text-slate-400">
            {{ formatDate(day) }}
          </div>
          <ol class="ml-2 space-y-3 border-l border-line pl-5 dark:border-slate-800">
            <li
              v-for="incident in dayIncidents"
              :key="incident.incidentId"
              class="relative"
            >
              <span
                class="absolute -left-[27px] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-surface dark:ring-slate-950"
                :class="incident.status === 'resolved' ? 'bg-success' : 'bg-danger'"
                aria-hidden="true"
              />
              <div class="card p-4">
                <div class="flex flex-wrap items-center gap-2">
                  <span
                    class="rounded-md px-2 py-0.5 text-xs font-semibold"
                    :class="incident.status === 'resolved'
                      ? 'bg-success/10 text-success dark:text-emerald-400'
                      : 'bg-danger/10 text-danger dark:text-red-400'"
                  >
                    {{ incident.status === 'resolved' ? t('status.incidentResolved') : t('status.incidentInvestigating') }}
                  </span>
                  <span class="font-semibold">{{ incidentTitle(incident) }}</span>
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
            </li>
          </ol>
        </div>
      </section>

      <p class="pb-2 text-center text-xs text-muted dark:text-slate-500">
        {{ t('status.autoRefresh') }}
      </p>
    </template>

    <template v-else-if="loading">
      <div class="h-14 animate-pulse rounded-lg bg-surface-muted dark:bg-slate-800" />
      <div class="h-64 animate-pulse rounded-lg bg-surface-muted dark:bg-slate-800" />
    </template>

    <!-- Day-cell detail tooltip: fixed-positioned at visual coordinates, so it
         stays accurate under the hive's scale transform. pointer-events-none
         keeps it from flickering when it appears under the cursor. -->
    <div
      v-if="tooltip"
      class="hive-tooltip card pointer-events-none fixed z-50 px-3 py-2 text-left text-xs shadow-lg"
      :style="{
        left: `${tooltip.x}px`,
        top: `${tooltip.y}px`,
        transform: tooltip.below ? 'translate(-50%, 0)' : 'translate(-50%, -100%)',
      }"
      role="tooltip"
      data-testid="day-tooltip"
    >
      <div class="flex items-center gap-1.5 font-semibold">
        <span class="hive-legend-day inline-block h-3 w-2.5" :class="indicatorColor(tooltip.day.indicator)" aria-hidden="true" />
        {{ componentText(tooltip.componentKey) }}
      </div>
      <div class="mt-1">{{ tooltip.day.date }}</div>
      <div class="text-muted">{{ indicatorText(tooltip.day.indicator) }}</div>
      <div v-if="tooltip.day.uptimePercent != null" class="tabular-nums">
        {{ t('status.barUptime', { percent: tooltip.day.uptimePercent.toFixed(2) }) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference '../styles/main.css';

/* No scroll containers: the whole hive scales down to the page width, so the
 * complete comb is always visible at any viewport size. */
.hive {
  position: relative;
  margin: 0 auto;
}
.hive-cell {
  position: absolute;
  left: var(--cell-x);
  top: var(--cell-y);
  pointer-events: none;
}
.hive-cell__rim,
.hive-cell__body::before,
.hive-day,
.hive-fill,
.hive-fill__hex,
.hive-legend-day {
  clip-path: polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%);
}
.hive-cell__rim {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.hive-cell__body::before {
  content: '';
  position: absolute;
  inset: 2px;
  background: var(--color-surface);
  z-index: -1;
}
.hive-cell__body {
  position: relative;
  isolation: isolate;
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}
.hive-history {
  position: absolute;
  inset: 2px;
  z-index: 1;
}
.hive-day,
.hive-fill__hex {
  position: absolute;
  display: block;
  min-height: 0;
  padding: 0;
  border: 0;
  transform: translate(-50%, -50%);
}
.hive-day {
  cursor: pointer;
  pointer-events: auto;
}
.hive-fill {
  position: absolute;
  inset: 2px;
  overflow: hidden;
  pointer-events: none;
}
.hive-cell--overall .hive-fill {
  opacity: 0.35;
}
.hive-overall {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.hive-day:hover,
.hive-day:focus-visible {
  filter: brightness(0.7);
}
.hive-legend {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-card);
  background: var(--color-surface);
}
.dark .hive-legend {
  border-color: var(--color-line);
}
.hive-legend__item {
  min-height: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 5px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;
}
.hive-legend__item:hover {
  background: color-mix(in srgb, var(--color-muted) 12%, transparent);
}
.hive-legend__item--active {
  background: color-mix(in srgb, var(--color-accent) 14%, transparent);
}
.hive-legend__swatch {
  display: inline-block;
  flex: none;
  width: 13px;
  height: 13px;
  clip-path: polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%);
}

/* Spotlight mode: the selected comb pops, the rest of the hive sinks into a
   gray overlay with its hovers disabled. */
.hive-cell--dim {
  filter: grayscale(1) opacity(0.32);
  pointer-events: none;
}
.hive-cell--focus {
  filter: drop-shadow(0 0 10px rgb(255 255 255 / 0.3));
}
.hive-tooltip {
  width: max-content;
  max-width: calc(100vw - 24px);
}
</style>
