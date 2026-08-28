<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type AuditEvent, type Report } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';

/**
 * Platform admin console (design §12.4 管理): abuse report queue, security
 * withdrawals (yank / quarantine by release id) and the global audit trail.
 * Review decisions stay in the reviewer queue.
 */
const { t } = useI18n();

const tab = ref<'upstreams' | 'listings' | 'reports' | 'withdraw' | 'audit'>('upstreams');

// ---- upstream aggregation (aggregation plan §3/§8) ----
interface UpstreamRow {
  upstreamId: string
  name: string
  marketplaceUrl: string
  targetNamespace: string
  adapterType?: string | null
  enabled?: boolean
  lastSyncAt?: string | null
  lastSyncOk?: boolean | null
  lastError?: string | null
}
interface SyncOutcome {
  upstream: string
  imported: number
  skipped: number
  failed: number
  errors: string[]
}
const upstreams = ref<UpstreamRow[]>([]);
const upstreamsLoading = ref(false);
const newName = ref('');
const newUrl = ref('');
const newNamespace = ref('');
const newAdapter = ref('AUTO');
const adding = ref(false);
const syncingId = ref<string | null>(null);
const lastSync = ref<SyncOutcome | null>(null);

async function loadUpstreams() {
  upstreamsLoading.value = true;
  try {
    upstreams.value = await api.getUpstreams();
  } finally {
    upstreamsLoading.value = false;
  }
}

async function addUpstream() {
  if (!newName.value || !newUrl.value || !newNamespace.value) return;
  adding.value = true;
  error.value = null;
  try {
    await api.createUpstream({
      name: newName.value,
      marketplaceUrl: newUrl.value,
      targetNamespace: newNamespace.value,
      adapterType: newAdapter.value,
    });
    newName.value = '';
    newUrl.value = '';
    newNamespace.value = '';
    await loadUpstreams();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    adding.value = false;
  }
}

async function syncNow(row: UpstreamRow) {
  syncingId.value = row.upstreamId;
  lastSync.value = null;
  error.value = null;
  try {
    lastSync.value = await api.syncUpstream(row.upstreamId);
    await Promise.all([loadUpstreams(), loadListings()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    syncingId.value = null;
  }
}

void [upstreamsLoading, adding, syncingId, lastSync];

// ---- listing curation (design §12.4 管理: 上下架/推荐) ----
interface AdminListing {
  listingId: string;
  coordinate: string;
  name: string;
  type: string;
  status: string;
  visibility: string;
  latestVersion: string | null;
  featured: boolean;
  downloads: number;
}
const listings = ref<AdminListing[]>([]);
const listingsLoading = ref(false);

async function loadListings() {
  listingsLoading.value = true;
  try {
    listings.value = await api.get<AdminListing[]>('/api/v1/admin/listings');
  } finally {
    listingsLoading.value = false;
  }
}

async function toggleVisibility(row: AdminListing) {
  const visibility = row.visibility === 'PUBLIC' ? 'UNLISTED' : 'PUBLIC';
  const updated = await api.post<AdminListing>(
    `/api/v1/admin/listings/${row.listingId}/visibility`, { visibility });
  Object.assign(row, updated);
}

async function toggleFeatured(row: AdminListing) {
  const updated = await api.post<AdminListing>(
    `/api/v1/admin/listings/${row.listingId}/featured`,
    { featured: !row.featured });
  Object.assign(row, updated);
}

void [listingsLoading];
const reports = ref<Report[]>([]);
const auditEvents = ref<AuditEvent[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const notes = ref<Record<string, string>>({});

function listingRoute(coordinate: string) {
  const parts = coordinate.replace('infinia://', '').split('/');
  return parts.length >= 3 ? `/listing/${parts[1]}/${parts[2]}` : '/browse';
}

const releaseId = ref('');
const reason = ref('');

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [r, a] = await Promise.all([
      api.get<Report[]>('/api/v1/admin/reports?status=OPEN'),
      api.get<AuditEvent[]>('/api/v1/admin/audit-events?limit=100'),
    ]);
    reports.value = r;
    auditEvents.value = a;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(() => {
  void load();
  void loadListings();
  void loadUpstreams();
});

async function resolve(report: Report, resolution: 'ACTIONED' | 'DISMISSED') {
  await api.post(`/api/v1/admin/reports/${report.reportId}/resolution`, {
    resolution,
    note: notes.value[report.reportId ?? ''] ?? '',
  });
  await load();
}

async function withdraw(kind: 'yank' | 'quarantine') {
  if (!releaseId.value) return;
  await api.post(`/api/v1/admin/releases/${releaseId.value}/${kind}`, { reason: reason.value });
  reason.value = '';
}
</script>

<template>
  <div class="space-y-8">
    <h1 class="text-2xl font-bold">{{ t('admin.title') }}</h1>
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else>
      <nav class="flex flex-wrap gap-1 border-b border-line dark:border-slate-800" role="tablist">
        <button
          v-for="key in ['upstreams', 'listings', 'reports', 'withdraw', 'audit'] as const"
          :key="key"
          role="tab"
          :aria-selected="tab === key"
          class="rounded-t-xl px-4 py-2 text-sm"
          :class="tab === key ? 'border-b-2 border-accent font-semibold' : 'text-muted'"
          @click="tab = key"
        >
          {{ t(`admin.${key}`) }}
        </button>
      </nav>

      <section v-if="tab === 'upstreams'" class="space-y-5">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.upstreamsHint') }}</p>

        <MagicCard class="p-6">
          <h3 class="mb-3 font-semibold">{{ t('admin.addUpstream') }}</h3>
          <form class="grid gap-3 sm:grid-cols-2" @submit.prevent="addUpstream">
            <label class="block text-sm">
              {{ t('admin.upstreamName') }}
              <input
                v-model="newName"
                required
                :placeholder="'superpowers'"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamUrl') }}
              <input
                v-model="newUrl"
                required
                type="url"
                placeholder="https://github.com/obra/superpowers"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamNamespace') }}
              <input
                v-model="newNamespace"
                required
                pattern="[a-z0-9][a-z0-9-]{0,62}"
                placeholder="superpowers"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamAdapter') }}
              <select
                v-model="newAdapter"
                class="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              >
                <option value="AUTO">AUTO</option>
                <option value="CLAUDE_MARKETPLACE">CLAUDE_MARKETPLACE</option>
                <option value="SKILL_REPOSITORY">SKILL_REPOSITORY</option>
                <option value="MCP_REGISTRY">MCP_REGISTRY</option>
              </select>
            </label>
            <div class="sm:col-span-2">
              <button
                :disabled="adding"
                class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white"
              >
                {{ adding ? t('common.loading') : t('admin.addUpstream') }}
              </button>
            </div>
          </form>
        </MagicCard>

        <LoadingGrid v-if="upstreamsLoading && !upstreams.length" />
        <EmptyState v-else-if="!upstreams.length" :title="t('admin.noUpstreams')" />
        <ul v-else class="space-y-2">
          <li
            v-for="row in upstreams"
            :key="row.upstreamId"
            class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line p-4 dark:border-slate-800"
          >
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <span class="font-semibold">{{ row.name }}</span>
                <Badge tone="muted">{{ row.targetNamespace }}</Badge>
                <Badge v-if="row.adapterType" tone="accent">{{ row.adapterType }}</Badge>
                <Badge
                  v-if="row.lastSyncOk === true"
                  tone="success"
                >{{ t('admin.syncOk') }}</Badge>
                <Badge
                  v-else-if="row.lastSyncOk === false"
                  tone="danger"
                >{{ t('admin.syncFailed') }}</Badge>
              </div>
              <code class="block truncate text-xs text-muted">{{ row.marketplaceUrl }}</code>
              <p
                v-if="row.lastError"
                class="mt-1 max-w-2xl text-xs text-red-600 dark:text-red-400"
              >
                {{ row.lastError }}
              </p>
            </div>
            <div class="flex items-center gap-2">
              <span v-if="row.lastSyncAt" class="text-xs text-muted">
                {{ new Date(row.lastSyncAt).toLocaleString() }}
              </span>
              <button
                :disabled="syncingId === row.upstreamId"
                class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                @click="syncNow(row)"
              >
                <span v-if="syncingId === row.upstreamId" class="mr-1 inline-block cx-spin" />
                {{ t('admin.syncNow') }}
              </button>
            </div>
          </li>
        </ul>

        <div
          v-if="lastSync"
          class="rounded-xl border border-line p-4 text-sm dark:border-slate-800"
          role="status"
        >
          <strong>{{ lastSync.upstream }}</strong>:
          {{ t('admin.syncImported', { n: lastSync.imported }) }} ·
          {{ t('admin.syncSkipped', { n: lastSync.skipped }) }} ·
          {{ t('admin.syncFailedCount', { n: lastSync.failed }) }}
          <ul v-if="lastSync.errors.length" class="mt-2 space-y-1 text-xs text-red-600 dark:text-red-400">
            <li v-for="e in lastSync.errors" :key="e">{{ e }}</li>
          </ul>
        </div>
      </section>

      <section v-if="tab === 'listings'" class="space-y-3">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.listingsHint') }}</p>
        <LoadingGrid v-if="listingsLoading && !listings.length" />
        <EmptyState v-else-if="!listings.length" :title="t('common.empty')" />
        <div v-else class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="text-muted">
                <th class="p-2">{{ t('admin.listingName') }}</th>
                <th class="p-2">{{ t('common.type') }}</th>
                <th class="p-2">{{ t('publisher.version') }}</th>
                <th class="p-2">{{ t('admin.visibility') }}</th>
                <th class="p-2">{{ t('admin.featured') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in listings" :key="row.listingId" class="border-t border-line dark:border-slate-800">
                <td class="p-2">
                  <RouterLink :to="listingRoute(row.coordinate)" class="font-medium hover:text-accent">
                    {{ row.name }}
                  </RouterLink>
                  <code class="block text-xs text-muted">{{ row.coordinate }}</code>
                </td>
                <td class="p-2">{{ t(`type.${row.type}`) }}</td>
                <td class="p-2">{{ row.latestVersion ? 'v' + row.latestVersion : '—' }}</td>
                <td class="p-2">
                  <button
                    class="rounded-lg px-3 py-1.5 text-xs font-semibold"
                    :class="row.visibility === 'PUBLIC'
                      ? 'border border-line text-muted dark:border-slate-800'
                      : 'bg-red-600 text-white'"
                    @click="toggleVisibility(row)"
                  >
                    {{ row.visibility === 'PUBLIC' ? t('admin.delist') : t('admin.relist') }}
                  </button>
                </td>
                <td class="p-2">
                  <button
                    class="rounded-lg px-3 py-1.5 text-xs font-semibold"
                    :class="row.featured
                      ? 'bg-amber-500 text-white'
                      : 'border border-line text-muted dark:border-slate-800'"
                    :aria-pressed="row.featured"
                    @click="toggleFeatured(row)"
                  >
                    {{ row.featured ? '★ ' + t('admin.featured') : t('admin.feature') }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="tab === 'reports'" class="space-y-4">
        <EmptyState v-if="!reports.length" :title="t('admin.noReports')" />
        <MagicCard v-for="report in reports" :key="report.reportId" class="p-6">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 class="font-semibold">{{ report.listingName }}</h2>
              <code class="text-xs text-muted">{{ report.listingCoordinate }}</code>
            </div>
            <div class="flex items-center gap-2">
              <Badge tone="danger">{{ t(`admin.reason.${report.reason}`) }}</Badge>
              <Badge tone="muted">{{ report.status }}</Badge>
            </div>
          </div>
          <p v-if="report.details" class="mt-3 text-sm">{{ report.details }}</p>
          <p class="mt-2 text-xs text-muted">{{ t('admin.reportedAt') }}: {{ report.createdAt }}</p>
          <div class="mt-4 flex flex-col gap-2 sm:flex-row">
            <textarea
              v-model="notes[report.reportId ?? '']"
              :placeholder="t('admin.resolutionNote')"
              rows="2"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <div class="flex gap-2">
              <button
                class="rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white"
                @click="resolve(report, 'ACTIONED')"
              >
                {{ t('admin.action') }}
              </button>
              <button
                class="rounded-xl border border-line px-4 py-2 text-sm dark:border-slate-800"
                @click="resolve(report, 'DISMISSED')"
              >
                {{ t('admin.dismiss') }}
              </button>
            </div>
          </div>
        </MagicCard>
      </section>

      <section v-if="tab === 'withdraw'" class="space-y-4">
        <p class="text-sm text-muted">{{ t('admin.withdrawHint') }}</p>
        <MagicCard class="p-6">
          <form class="flex flex-col gap-2 sm:flex-row" @submit.prevent="withdraw('yank')">
            <input
              v-model="releaseId"
              required
              placeholder="release UUID"
              class="w-full rounded-xl border border-line px-3 py-2 font-mono text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <input
              v-model="reason"
              :placeholder="t('admin.reasonLabel')"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <button class="rounded-xl border border-line px-4 py-2 text-sm font-semibold dark:border-slate-800">
              {{ t('admin.yank') }}
            </button>
          </form>
          <button
            class="mt-3 w-full rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white"
            :disabled="!releaseId"
            @click="withdraw('quarantine')"
          >
            {{ t('admin.quarantine') }}
          </button>
          <p class="mt-2 text-xs text-muted">{{ t('admin.quarantineHint') }}</p>
        </MagicCard>
      </section>

      <section v-if="tab === 'audit'">
        <EmptyState v-if="!auditEvents.length" :title="t('common.empty')" />
        <ul v-else class="space-y-1 text-xs">
          <li
            v-for="event in auditEvents"
            :key="event.eventId"
            class="rounded-lg border border-line px-3 py-2 dark:border-slate-800"
          >
            <span class="text-muted">{{ event.occurredAt }}</span>
            · <span class="font-semibold">{{ event.action }}</span>
            · {{ event.resourceType }}/<code>{{ event.resourceId }}</code>
            · <span class="text-muted">{{ event.actorType }}:{{ event.actorId }}</span>
            <span v-if="event.afterSummary"> → {{ event.afterSummary }}</span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
