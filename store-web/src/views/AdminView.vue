<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type AdminAppRelease, type AdminAppUploadSession, type AdminListing, type AdminUser, type AuditEvent, type DataSourceStatus, type RemoteDatabase, type RemoteDatabaseTestResult, type Report } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import { beeMark } from '../bee-levels';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';

/**
 * Platform admin console (design §12.4 管理): user management (Infinia Level),
 * listing curation incl. Infinia Level gates, abuse report queue, security
 * withdrawals (yank / quarantine by release id) and the global audit trail.
 * Review decisions stay in the reviewer queue.
 */
const { t } = useI18n();

const tab = ref<'users' | 'databases' | 'upstreams' | 'listings' | 'reports' | 'appRelease' | 'withdraw' | 'audit'>('users');

// ---- remote databases (远程数据库配置) ----
const databases = ref<RemoteDatabase[]>([]);
const dataSourceStatus = ref<DataSourceStatus | null>(null);
const databasesLoading = ref(false);
const databasesError = ref<string | null>(null);
const dbBusyId = ref<string | null>(null);
const lastProbe = ref<RemoteDatabaseTestResult | null>(null);
const newDb = ref({ name: '', jdbcUrl: '', username: '', password: '' });
const addingDb = ref(false);

async function loadDatabases() {
  databasesLoading.value = true;
  databasesError.value = null;
  try {
    const [rows, status] = await Promise.all([
      api.getRemoteDatabases(),
      api.getDataSourceStatus(),
    ]);
    databases.value = rows;
    dataSourceStatus.value = status;
  } catch (e) {
    databasesError.value = e instanceof Error ? e.message : String(e);
  } finally {
    databasesLoading.value = false;
  }
}

async function addDatabase() {
  if (!newDb.value.name || !newDb.value.jdbcUrl || !newDb.value.username || !newDb.value.password) {
    return;
  }
  addingDb.value = true;
  databasesError.value = null;
  try {
    await api.createRemoteDatabase({ ...newDb.value });
    newDb.value = { name: '', jdbcUrl: '', username: '', password: '' };
    await loadDatabases();
  } catch (e) {
    databasesError.value = e instanceof Error ? e.message : String(e);
  } finally {
    addingDb.value = false;
  }
}

async function probeDatabase(row: RemoteDatabase) {
  dbBusyId.value = row.databaseId;
  databasesError.value = null;
  lastProbe.value = null;
  try {
    lastProbe.value = await api.testRemoteDatabase(row.databaseId);
    await loadDatabases();
  } catch (e) {
    databasesError.value = e instanceof Error ? e.message : String(e);
  } finally {
    dbBusyId.value = null;
  }
}

async function toggleActivation(row: RemoteDatabase) {
  dbBusyId.value = row.databaseId;
  databasesError.value = null;
  try {
    await api.setRemoteDatabaseActivation(row.databaseId, !row.enabled);
    await loadDatabases();
  } catch (e) {
    databasesError.value = e instanceof Error ? e.message : String(e);
    await loadDatabases();
  } finally {
    dbBusyId.value = null;
  }
}

async function removeDatabase(row: RemoteDatabase) {
  dbBusyId.value = row.databaseId;
  databasesError.value = null;
  try {
    await api.deleteRemoteDatabase(row.databaseId);
    await loadDatabases();
  } catch (e) {
    databasesError.value = e instanceof Error ? e.message : String(e);
  } finally {
    dbBusyId.value = null;
  }
}

// ---- user management (Infinia Level · 用户管理) ----
const users = ref<AdminUser[]>([]);
const usersLoading = ref(false);
const usersError = ref<string | null>(null);
const savingUserId = ref<string | null>(null);
const userSearch = ref('');
const BEE_LEVELS = [0, 1, 2, 3, 4];
/** Option label mirrors the badge: the emblem changes with the level. */
function beeLevelLabel(level: number, suffix = ''): string {
  return `${beeMark(level).emblem} ${t(`beeLevel.${level}`)} · Lv${level}${suffix}`;
}

const filteredUsers = ref<AdminUser[]>([]);
function applyUserFilter() {
  const q = userSearch.value.trim().toLowerCase();
  filteredUsers.value = q
    ? users.value.filter(
        (u) =>
          u.email.toLowerCase().includes(q)
          || (u.displayName ?? '').toLowerCase().includes(q),
      )
    : [...users.value];
}

async function loadUsers() {
  usersLoading.value = true;
  usersError.value = null;
  try {
    users.value = await api.getAdminUsers();
    applyUserFilter();
  } catch (e) {
    usersError.value = e instanceof Error ? e.message : String(e);
  } finally {
    usersLoading.value = false;
  }
}

async function setUserBeeLevel(user: AdminUser, level: number) {
  if (level === user.beeLevel) return;
  savingUserId.value = user.userId;
  usersError.value = null;
  try {
    const updated = await api.updateAdminUser(user.userId, { beeLevel: level });
    Object.assign(user, updated);
  } catch (e) {
    usersError.value = e instanceof Error ? e.message : String(e);
    await loadUsers();
  } finally {
    savingUserId.value = null;
  }
}

async function toggleUserStatus(user: AdminUser) {
  savingUserId.value = user.userId;
  usersError.value = null;
  try {
    const updated = await api.updateAdminUser(user.userId, {
      status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
    });
    Object.assign(user, updated);
  } catch (e) {
    usersError.value = e instanceof Error ? e.message : String(e);
    await loadUsers();
  } finally {
    savingUserId.value = null;
  }
}

function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}

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

// ---- listing curation (design §12.4 管理: 上下架/推荐/Infinia Level 门槛) ----
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

async function setListingLevel(row: AdminListing, level: number) {
  if (level === row.minBeeLevel) return;
  const updated = await api.setListingMinBeeLevel(row.listingId, level);
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
  void loadUsers();
  void loadDatabases();
  void loadListings();
  void loadUpstreams();
  void loadAppReleases();
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

// ---- manual host-app update upload (手动上传主程序更新包) ----
const appReleases = ref<AdminAppRelease[]>([]);
const appFileInput = ref<HTMLInputElement | null>(null);
const appFile = ref<File | null>(null);
const appChangelog = ref('');
const appUploading = ref(false);
const appMessage = ref('');
const appError = ref<string | null>(null);

/**
 * Mirrors the server's inference (AdminAppReleaseController): the filename is
 * the single source of truth — version + channel are read straight from it, so
 * the admin only picks a file.
 */
const appDetected = computed(() => {
  if (!appFile.value) return null;
  const m = appFile.value.name.match(/(\d+\.\d+\.\d+(?:-(?:alpha|beta|rc|nightly)(?:\.\d+)*)?)/);
  if (!m) return null;
  const lower = m[1].toLowerCase();
  const dash = lower.indexOf('-');
  const label = dash < 0 ? '' : lower.slice(dash + 1);
  const channel = label.startsWith('alpha') ? 'alpha'
    : label.startsWith('nightly') ? 'nightly'
    : label.startsWith('beta') || label.startsWith('rc') ? 'beta'
    : 'stable';
  return { version: m[1], channel };
});

function onAppFileChange() {
  appFile.value = appFileInput.value?.files?.[0] ?? null;
  appError.value = null;
  if (appFile.value && !appDetected.value) {
    appError.value = t('admin.appVersionNotDetected');
  }
}

async function loadAppReleases() {
  try {
    appReleases.value = await api.get<AdminAppRelease[]>('/api/v1/admin/app-releases');
  } catch {
    appReleases.value = [];
  }
}

/**
 * Intranet manual update (the store replaces the FY-Proxy distribution center):
 * start (version/channel inferred from the filename server-side) → presigned
 * PUT of the package bytes → publish immediately.
 */
async function uploadAppPackage() {
  const file = appFile.value;
  if (!file) return;
  appUploading.value = true;
  appMessage.value = '';
  appError.value = null;
  try {
    const session = await api.post<AdminAppUploadSession>('/api/v1/admin/app-releases', {
      changelog: appChangelog.value || undefined,
      filename: file.name,
      size: file.size,
    });
    await api.putRaw(session.uploadUrl, await file.arrayBuffer());
    appMessage.value = t('admin.appUploaded');
    const published = await api.post<AdminAppRelease>(
      `/api/v1/admin/app-releases/${session.releaseId}/publish`);
    appMessage.value = t('admin.appPublished', { version: published.version });
    appChangelog.value = '';
    appFile.value = null;
    if (appFileInput.value) appFileInput.value.value = '';
    await loadAppReleases();
  } catch (e) {
    appError.value = e instanceof Error ? e.message : String(e);
  } finally {
    appUploading.value = false;
  }
}

async function deleteAppRelease(rel: AdminAppRelease) {
  if (!window.confirm(t('admin.appDeleteConfirm', { version: rel.version }))) return;
  appError.value = null;
  try {
    await api.delete(`/api/v1/admin/app-releases/${rel.releaseId}`);
    await loadAppReleases();
  } catch (e) {
    appError.value = e instanceof Error ? e.message : String(e);
  }
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
          v-for="key in ['users', 'databases', 'upstreams', 'listings', 'reports', 'appRelease', 'withdraw', 'audit'] as const"
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

      <section v-if="tab === 'users'" class="space-y-3">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.usersHint') }}</p>
        <p
          v-if="usersError"
          class="rounded-xl border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
          role="alert"
        >
          {{ usersError }}
        </p>
        <input
          v-model="userSearch"
          :placeholder="t('admin.userSearch')"
          class="w-full max-w-md rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
          @input="applyUserFilter"
        />
        <LoadingGrid v-if="usersLoading && !users.length" />
        <EmptyState v-else-if="!filteredUsers.length" :title="t('common.empty')" />
        <div v-else class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="text-muted">
                <th class="p-2">{{ t('admin.userAccount') }}</th>
                <th class="p-2">{{ t('account.roles') }}</th>
                <th class="p-2">{{ t('beeLevel.title') }}</th>
                <th class="p-2">{{ t('admin.userStatus') }}</th>
                <th class="p-2">{{ t('admin.userLastLogin') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredUsers"
                :key="row.userId"
                class="border-t border-line dark:border-slate-800"
              >
                <td class="p-2">
                  <div class="font-medium">{{ row.displayName }}</div>
                  <code class="block text-xs text-muted">{{ row.email }}</code>
                </td>
                <td class="p-2">
                  <span class="flex flex-wrap gap-1">
                    <Badge v-for="role in row.roles" :key="role" tone="muted">
                      {{ t(`role.${role}`) }}
                    </Badge>
                  </span>
                </td>
                <td class="p-2">
                  <div class="flex items-center gap-2">
                    <BeeLevelBadge :level="row.beeLevel" />
                    <select
                      :value="row.beeLevel"
                      :disabled="savingUserId === row.userId"
                      :aria-label="t('admin.setBeeLevel')"
                      class="rounded-lg border border-line bg-surface px-2 py-1 text-xs dark:border-slate-800 dark:bg-slate-900"
                      @change="setUserBeeLevel(row, Number(($event.target as HTMLSelectElement).value))"
                    >
                      <option v-for="level in BEE_LEVELS" :key="level" :value="level">
                        {{ beeLevelLabel(level) }}
                      </option>
                    </select>
                  </div>
                </td>
                <td class="p-2">
                  <button
                    class="rounded-lg px-3 py-1.5 text-xs font-semibold"
                    :class="row.status === 'ACTIVE'
                      ? 'border border-line text-muted dark:border-slate-800'
                      : 'bg-red-600 text-white'"
                    :disabled="savingUserId === row.userId"
                    @click="toggleUserStatus(row)"
                  >
                    {{ row.status === 'ACTIVE' ? t('admin.disable') : t('admin.enable') }}
                  </button>
                </td>
                <td class="p-2 text-xs text-muted">{{ formatDateTime(row.lastLoginAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="tab === 'databases'" class="space-y-5">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.databasesHint') }}</p>
        <p
          v-if="databasesError"
          class="rounded-xl border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
          role="alert"
        >
          {{ databasesError }}
        </p>

        <MagicCard v-if="dataSourceStatus" class="p-6">
          <h3 class="mb-3 font-semibold">{{ t('admin.currentDataSource') }}</h3>
          <dl class="grid gap-2 text-sm sm:grid-cols-2">
            <div>
              <dt class="text-muted">{{ t('admin.dbProduct') }}</dt>
              <dd class="font-medium">
                {{ dataSourceStatus.productName ?? '—' }}
                {{ dataSourceStatus.productVersion ? `(${dataSourceStatus.productVersion})` : '' }}
              </dd>
            </div>
            <div>
              <dt class="text-muted">{{ t('admin.dbUser') }}</dt>
              <dd class="font-medium">{{ dataSourceStatus.username ?? '—' }}</dd>
            </div>
            <div class="sm:col-span-2">
              <dt class="text-muted">{{ t('admin.dbUrl') }}</dt>
              <dd><code class="break-all text-xs">{{ dataSourceStatus.url ?? '—' }}</code></dd>
            </div>
            <div class="sm:col-span-2">
              <dt class="text-muted">{{ t('admin.remoteOverride') }}</dt>
              <dd class="mt-1 flex flex-wrap items-center gap-2">
                <Badge v-if="dataSourceStatus.remoteOverrideActive" tone="success">
                  {{ t('admin.overrideActive') }}{{ dataSourceStatus.overrideName
                    ? ` · ${dataSourceStatus.overrideName}` : '' }}
                </Badge>
                <Badge v-else tone="muted">{{ t('admin.overrideInactive') }}</Badge>
              </dd>
            </div>
          </dl>
        </MagicCard>

        <MagicCard class="p-6">
          <h3 class="mb-3 font-semibold">{{ t('admin.addDatabase') }}</h3>
          <form class="grid gap-3 sm:grid-cols-2" @submit.prevent="addDatabase">
            <label class="block text-sm">
              {{ t('admin.dbName') }}
              <input
                v-model="newDb.name"
                required
                maxlength="100"
                placeholder="production-pg"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.dbUser') }}
              <input
                v-model="newDb.username"
                required
                maxlength="200"
                placeholder="store"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm sm:col-span-2">
              {{ t('admin.dbJdbcUrl') }}
              <input
                v-model="newDb.jdbcUrl"
                required
                maxlength="500"
                placeholder="jdbc:postgresql://db.example.com:5432/store"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 font-mono text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.dbPassword') }}
              <input
                v-model="newDb.password"
                required
                type="password"
                autocomplete="new-password"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <div class="sm:self-end">
              <button
                :disabled="addingDb"
                class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
              >
                {{ addingDb ? t('common.loading') : t('admin.addDatabase') }}
              </button>
            </div>
            <p class="text-xs text-muted sm:col-span-2">{{ t('admin.dbSecurityHint') }}</p>
          </form>
        </MagicCard>

        <LoadingGrid v-if="databasesLoading && !databases.length" />
        <EmptyState v-else-if="!databases.length" :title="t('admin.noDatabases')" />
        <ul v-else class="space-y-2">
          <li
            v-for="row in databases"
            :key="row.databaseId"
            class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line p-4 dark:border-slate-800"
          >
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <span class="font-semibold">{{ row.name }}</span>
                <Badge v-if="row.enabled" tone="success">{{ t('admin.dbEnabled') }}</Badge>
                <Badge
                  v-if="row.lastTestOk === true"
                  tone="success"
                >{{ t('admin.dbTestOk') }}</Badge>
                <Badge
                  v-else-if="row.lastTestOk === false"
                  tone="danger"
                >{{ t('admin.dbTestFailed') }}</Badge>
              </div>
              <code class="block truncate text-xs text-muted">{{ row.jdbcUrl }}</code>
              <p v-if="row.lastTestError" class="mt-1 text-xs text-red-600 dark:text-red-400">
                {{ row.lastTestError }}
              </p>
              <p v-if="row.lastTestedAt" class="text-xs text-muted">
                {{ t('admin.dbLastTested') }}: {{ formatDateTime(row.lastTestedAt) }}
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <button
                :disabled="dbBusyId === row.databaseId"
                class="rounded-xl border border-line px-3 py-2 text-sm font-semibold disabled:opacity-50 dark:border-slate-800"
                @click="probeDatabase(row)"
              >
                {{ t('admin.dbTest') }}
              </button>
              <button
                :disabled="dbBusyId === row.databaseId"
                class="rounded-xl px-3 py-2 text-sm font-semibold disabled:opacity-50"
                :class="row.enabled
                  ? 'border border-line text-muted dark:border-slate-800'
                  : 'bg-accent text-white'"
                @click="toggleActivation(row)"
              >
                {{ row.enabled ? t('admin.dbDeactivate') : t('admin.dbActivate') }}
              </button>
              <button
                :disabled="dbBusyId === row.databaseId"
                class="rounded-xl bg-red-600 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50"
                @click="removeDatabase(row)"
              >
                {{ t('admin.dbDelete') }}
              </button>
            </div>
          </li>
        </ul>

        <div
          v-if="lastProbe"
          class="rounded-xl border border-line p-4 text-sm dark:border-slate-800"
          role="status"
        >
          <template v-if="lastProbe.ok">
            ✅ {{ t('admin.dbTestOk') }} — {{ lastProbe.productName }}
            {{ lastProbe.productVersion }}
          </template>
          <template v-else>
            ❌ {{ t('admin.dbTestFailed') }} — {{ lastProbe.error }}
          </template>
        </div>
        <p class="text-xs text-muted">{{ t('admin.dbActivateHint') }}</p>
      </section>

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
                <th class="p-2">{{ t('admin.minBeeLevel') }}</th>
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
                <td class="p-2">
                  <select
                    :value="row.minBeeLevel"
                    :aria-label="t('admin.minBeeLevel')"
                    class="rounded-lg border border-line bg-surface px-2 py-1 text-xs dark:border-slate-800 dark:bg-slate-900"
                    @change="setListingLevel(row, Number(($event.target as HTMLSelectElement).value))"
                  >
                    <option v-for="level in BEE_LEVELS" :key="level" :value="level">
                      {{ level === 0
                        ? t('admin.beeLevelPublic')
                        : beeLevelLabel(level, '+') }}
                    </option>
                  </select>
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

      <section v-if="tab === 'appRelease'" class="space-y-4">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.appReleaseHint') }}</p>
        <MagicCard class="p-6">
          <h3 class="mb-3 font-semibold">{{ t('admin.appReleaseUpload') }}</h3>
          <form class="flex flex-col gap-3" @submit.prevent="uploadAppPackage">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
              <!-- Visible picker button: the bare file input renders without a
                   clickable button in several browsers. -->
              <input
                ref="appFileInput"
                type="file"
                accept=".zip,.exe,.msi,.dmg,.pkg,.deb,.AppImage,.jar,.tar.gz"
                class="hidden"
                @change="onAppFileChange"
              />
              <button
                type="button"
                class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white"
                @click="appFileInput?.click()"
              >
                {{ t('admin.appChooseFile') }}
              </button>
              <span class="min-w-0 flex-1 truncate text-sm" :class="appFile ? '' : 'text-muted dark:text-slate-400'">
                {{ appFile ? appFile.name : t('admin.appNoFile') }}
              </span>
              <span v-if="appDetected" class="shrink-0">
                <Badge tone="accent">v{{ appDetected.version }}</Badge>
                <Badge tone="muted" class="ml-1">{{ appDetected.channel }}</Badge>
              </span>
            </div>
            <label class="block w-full text-sm">
              {{ t('admin.appChangelog') }}
              <textarea
                v-model="appChangelog"
                rows="2"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <button
              :disabled="appUploading || !appFile || !appDetected"
              class="self-start whitespace-nowrap rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
            >
              {{ appUploading ? t('admin.appUploading') : t('admin.appUploadAndPublish') }}
            </button>
          </form>
          <p v-if="appMessage" class="mt-2 text-sm text-green-600 dark:text-green-400">{{ appMessage }}</p>
          <p v-if="appError" class="mt-2 text-sm text-red-600 dark:text-red-400">{{ appError }}</p>
        </MagicCard>

        <h3 class="font-semibold">{{ t('admin.appReleaseHistory') }}</h3>
        <EmptyState v-if="!appReleases.length" :title="t('admin.appReleaseEmpty')" />
        <ul v-else class="space-y-1 text-sm">
          <li
            v-for="rel in appReleases"
            :key="rel.releaseId"
            class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line px-3 py-2 dark:border-slate-800"
          >
            <div class="flex flex-wrap items-center gap-2">
              <span class="font-semibold">v{{ rel.version }}</span>
              <Badge tone="muted">{{ rel.channel }}</Badge>
              <Badge tone="accent">{{ rel.status }}</Badge>
              <span class="text-xs text-muted">
                {{ (rel.artifacts ?? []).map((a) => a.filename).join(', ') }}
              </span>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-xs text-muted">{{ rel.publishedAt ?? rel.releaseId }}</span>
              <button
                class="rounded-lg border border-line px-2.5 py-1 text-xs font-semibold text-red-600 dark:border-slate-800 dark:text-red-400"
                @click="deleteAppRelease(rel)"
              >
                {{ t('admin.appDelete') }}
              </button>
            </div>
          </li>
        </ul>
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
