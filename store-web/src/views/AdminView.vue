<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, formatFen, type AdminAppRelease, type AdminAppUploadSession, type AdminListing, type AdminMembershipOrder, type AdminMembershipPlan, type AdminUser, type AuditEvent, type DataSourceStatus, type PublisherRelease, type RemoteDatabase, type RemoteDatabaseTestResult, type Report, type Upstream, type UpstreamSyncRun } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';
import StateChip from '../components/StateChip.vue';
import SelectMenu from '../components/SelectMenu.vue';
import { formatDate, formatDateTime } from '../utils/format';

/**
 * Platform admin console (design §12.4 管理): user management (Infinia Level),
 * listing curation incl. Infinia Level gates, abuse report queue, security
 * withdrawals (yank / quarantine by release id) and the global audit trail.
 * Review decisions stay in the reviewer queue.
 */
const { t } = useI18n();

type AdminTab = 'users' | 'membership' | 'databases' | 'upstreams' | 'listings' | 'reports' | 'appRelease' | 'withdraw' | 'audit';
const tab = ref<AdminTab>('users');

/**
 * Grouped navigation (design §12.4 管理): eight flat tabs read as one noisy
 * row, so the console is organized into four labeled sections — people,
 * catalog supply, trust & safety, infrastructure.
 */
const navGroups = computed(() =>
  [
    { labelKey: 'admin.group.users', items: ['users', 'membership'] },
    { labelKey: 'admin.group.content', items: ['listings', 'upstreams', 'appRelease'] },
    { labelKey: 'admin.group.trust', items: ['reports', 'withdraw', 'audit'] },
    { labelKey: 'admin.group.system', items: ['databases'] },
  ] as { labelKey: string; items: AdminTab[] }[],
);

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
function beeLevelLabel(level: number, suffix = ''): string {
  return `${t(`beeLevel.${level}`)} · Lv${level}${suffix}`;
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

// ---- membership plans & orders (管理 · 会员套餐) ----
const membershipPlans = ref<AdminMembershipPlan[]>([]);
const membershipOrders = ref<AdminMembershipOrder[]>([]);
const membershipLoading = ref(false);
const membershipError = ref<string | null>(null);
const busyPlanId = ref<string | null>(null);
/** Editable draft for the create row; levels 1-4 only (LARVA is not for sale). */
const PURCHASABLE_LEVELS = [1, 2, 3, 4];
const newPlan = ref({ beeLevel: 1, durationDays: 30, priceFen: 600, sort: 1, externalUrl: '' });

async function loadMembership() {
  membershipLoading.value = true;
  membershipError.value = null;
  try {
    const [plans, orders] = await Promise.all([
      api.getAdminMembershipPlans(),
      api.getAdminMembershipOrders(),
    ]);
    membershipPlans.value = plans;
    membershipOrders.value = orders;
  } catch (e) {
    membershipError.value = e instanceof Error ? e.message : String(e);
  } finally {
    membershipLoading.value = false;
  }
}

/** The create form works in yuan for readability; the API speaks fen. */
const newPlanYuan = computed({
  get: () => (newPlan.value.priceFen / 100).toString(),
    set: (value: string) => {
    newPlan.value.priceFen = Math.round(Number.parseFloat(value || '0') * 100) || 0;
  },
});

async function addPlan() {
  membershipError.value = null;
  try {
    await api.createAdminMembershipPlan({ ...newPlan.value });
    await loadMembership();
  } catch (e) {
    membershipError.value = e instanceof Error ? e.message : String(e);
  }
}

async function updatePlan(plan: AdminMembershipPlan,
    body: Partial<Omit<AdminMembershipPlan, 'externalUrl'>> & { externalUrl?: string }) {
  busyPlanId.value = plan.planId;
  membershipError.value = null;
  try {
    const updated = await api.updateAdminMembershipPlan(plan.planId, {
      beeLevel: body.beeLevel ?? plan.beeLevel,
      durationDays: body.durationDays ?? plan.durationDays,
      priceFen: body.priceFen ?? plan.priceFen,
      active: body.active ?? plan.active,
      sort: body.sort ?? plan.sort,
      // Blank clears the link; undefined keeps the stored one (partial update).
      externalUrl: body.externalUrl !== undefined ? body.externalUrl : plan.externalUrl ?? '',
    });
    Object.assign(plan, updated);
  } catch (e) {
    membershipError.value = e instanceof Error ? e.message : String(e);
    await loadMembership();
  } finally {
    busyPlanId.value = null;
  }
}

async function removePlan(plan: AdminMembershipPlan) {
  if (!window.confirm(t('admin.membershipDeleteConfirm', { level: plan.beeLevel, n: plan.durationDays }))) {
    return;
  }
  busyPlanId.value = plan.planId;
  membershipError.value = null;
  try {
    await api.deleteAdminMembershipPlan(plan.planId);
    await loadMembership();
  } catch (e) {
    membershipError.value = e instanceof Error ? e.message : String(e);
  } finally {
    busyPlanId.value = null;
  }
}

function membershipOrderTone(status: string): 'success' | 'muted' | 'gold' {
  if (status === 'PAID') return 'success';
  if (status === 'PENDING') return 'gold';
  return 'muted';
}

// ---- upstream aggregation (aggregation plan §3/§8) ----
const upstreams = ref<Upstream[]>([]);
const upstreamsLoading = ref(false);
const newName = ref('');
const newUrl = ref('');
const newNamespace = ref('');
const newAdapter = ref('AUTO');
const adding = ref(false);
const syncingId = ref<string | null>(null);
/** Sync-log viewer: which source is open and its recent runs. */
const logUpstream = ref<Upstream | null>(null);
const logRuns = ref<UpstreamSyncRun[] | null>(null);
const logLoading = ref(false);

async function loadUpstreams() {
  upstreamsLoading.value = true;
  try {
    upstreams.value = await api.getUpstreams();
  } finally {
    upstreamsLoading.value = false;
  }
}

/**
 * Registration opens a background run server-side, so the list already shows
 * the new source with 正在同步; the poll settles it into 成功/失败 without
 * the admin babysitting a request that takes minutes.
 */
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

async function syncNow(row: Upstream) {
  syncingId.value = row.upstreamId;
  error.value = null;
  try {
    await api.syncUpstream(row.upstreamId);
    await Promise.all([loadUpstreams(), loadListings()]);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    syncingId.value = null;
  }
}

async function openSyncLog(row: Upstream) {
  logUpstream.value = row;
  logRuns.value = null;
  logLoading.value = true;
  try {
    logRuns.value = await api.getUpstreamSyncRuns(row.upstreamId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    logUpstream.value = null;
  } finally {
    logLoading.value = false;
  }
}

function closeSyncLog() {
  logUpstream.value = null;
  logRuns.value = null;
}

function runStatusTone(status?: string | null): 'success' | 'danger' | 'muted' {
  if (status === 'OK') return 'success';
  if (status === 'PARTIAL') return 'danger';
  return 'muted';
}

function runStatusLabel(status?: string | null): string {
  if (status === 'RUNNING') return t('admin.syncSyncing');
  if (status === 'OK') return t('admin.runOk');
  if (status === 'PARTIAL') return t('admin.runPartial');
  return status ?? '—';
}

/** Refresh the list while a run is open so SYNCING settles on its own. */
let upstreamPoll: ReturnType<typeof setInterval> | null = null;
watch(
  () => tab.value === 'upstreams'
    && upstreams.value.some((row) => row.syncStatus === 'SYNCING'),
  (pollWanted) => {
    if (pollWanted && upstreamPoll === null) {
      upstreamPoll = setInterval(() => { void loadUpstreams(); }, 4000);
    } else if (!pollWanted && upstreamPoll !== null) {
      clearInterval(upstreamPoll);
      upstreamPoll = null;
    }
  },
);
onBeforeUnmount(() => {
  if (upstreamPoll !== null) clearInterval(upstreamPoll);
});

void [upstreamsLoading, adding, syncingId, logLoading];

// ---- listing curation (design §12.4 管理: 上下架/推荐/Infinia Level 门槛) ----
const listings = ref<AdminListing[]>([]);
const listingsLoading = ref(false);
const listingSearch = ref('');

/** 367+ rows render without pagination, so a client-side filter is the relief
 *  valve — same pattern as the user table above. */
const filteredListings = computed(() => {
  const q = listingSearch.value.trim().toLowerCase();
  if (!q) return listings.value;
  return listings.value.filter(
    (l) => l.name.toLowerCase().includes(q) || l.coordinate.toLowerCase().includes(q),
  );
});

async function loadListings() {
  listingsLoading.value = true;
  try {
    listings.value = await api.get<AdminListing[]>('/api/v1/admin/listings');
  } finally {
    listingsLoading.value = false;
  }
}

async function toggleVisibility(row: AdminListing) {
  // Delisting hides the listing from every catalog at once — worth a confirm.
  const delisting = row.visibility === 'PUBLIC';
  if (delisting && !window.confirm(t('admin.delistConfirm', { name: row.name }))) return;
  const visibility = delisting ? 'UNLISTED' : 'PUBLIC';
  try {
    const updated = await api.post<AdminListing>(
      `/api/v1/admin/listings/${row.listingId}/visibility`, { visibility });
    Object.assign(row, updated);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
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
  void loadMembership();
});

async function resolve(report: Report, resolution: 'ACTIONED' | 'DISMISSED') {
  await api.post(`/api/v1/admin/reports/${report.reportId}/resolution`, {
    resolution,
    note: notes.value[report.reportId ?? ''] ?? '',
  });
  await load();
}

// ---- security withdrawal (安全下架): pick listing → pick release → act ----
const withdrawListingId = ref('');
const withdrawReleases = ref<PublisherRelease[]>([]);
const withdrawLoading = ref(false);
const withdrawMessage = ref('');
const withdrawError = ref<string | null>(null);

const WITHDRAW_LISTING_OPTIONS = computed(() =>
  listings.value.map((l) => ({ value: l.listingId, label: l.name })));

/** Admins may read every listing's releases (owner check is skipped for
 *  PLATFORM_ADMIN), so the withdrawal form can offer versions by name. */
async function loadWithdrawReleases() {
  withdrawError.value = null;
  withdrawMessage.value = '';
  withdrawReleases.value = [];
  releaseId.value = '';
  if (!withdrawListingId.value) return;
  withdrawLoading.value = true;
  try {
    withdrawReleases.value = await api.get<PublisherRelease[]>(
      `/api/v1/publisher/listings/${withdrawListingId.value}/releases`,
    );
  } catch (e) {
    withdrawError.value = e instanceof Error ? e.message : String(e);
  } finally {
    withdrawLoading.value = false;
  }
}

const WITHDRAW_RELEASE_OPTIONS = computed(() =>
  withdrawReleases.value.map((r) => ({
    value: r.releaseId,
    label: `v${r.version} · ${t(`state.${r.status}`, r.status)}`,
  })));

function onWithdrawListingChange(value: string | number) {
  withdrawListingId.value = String(value);
  void loadWithdrawReleases();
}

function onWithdrawReleaseChange(value: string | number) {
  releaseId.value = String(value);
}

async function withdraw(kind: 'yank' | 'quarantine') {
  if (!releaseId.value) return;
  withdrawError.value = null;
  withdrawMessage.value = '';
  const row = withdrawReleases.value.find((r) => r.releaseId === releaseId.value);
  if (row && row.status !== 'PUBLISHED') {
    withdrawError.value = t('admin.withdrawNeedsPublished');
    return;
  }
  if (kind === 'quarantine' && !reason.value.trim()) {
    withdrawError.value = t('admin.withdrawReasonRequired');
    return;
  }
  if (!window.confirm(t(kind === 'yank' ? 'admin.yankConfirm' : 'admin.quarantineConfirm',
    { version: row?.version ?? releaseId.value }))) {
    return;
  }
  try {
    await api.post(`/api/v1/admin/releases/${releaseId.value}/${kind}`, { reason: reason.value });
    reason.value = '';
    await loadWithdrawReleases();
    // Set after the refresh: loadWithdrawReleases clears stale feedback.
    withdrawMessage.value = t(
      kind === 'yank' ? 'admin.yankDone' : 'admin.quarantineDone',
      { version: row?.version ?? '' },
    );
  } catch (e) {
    withdrawError.value = e instanceof Error ? e.message : String(e);
  }
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
      <div class="grid gap-8 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <!-- Grouped section nav: stacked sidebar on desktop, wrapping clusters on mobile. -->
        <nav
          class="flex flex-row flex-wrap gap-x-8 gap-y-5 self-start lg:sticky lg:top-20 lg:flex-col lg:gap-6"
          role="tablist"
          :aria-label="t('admin.title')"
        >
          <div v-for="group in navGroups" :key="group.labelKey" class="min-w-0">
            <p class="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted dark:text-slate-500">
              {{ t(group.labelKey) }}
            </p>
            <div class="flex flex-wrap gap-1 lg:flex-col lg:items-stretch">
              <button
                v-for="key in group.items"
                :key="key"
                role="tab"
                :aria-selected="tab === key"
                class="flex items-center rounded-lg px-3 py-1.5 text-left text-sm transition-colors [min-height:2.25rem]"
                :class="tab === key
                  ? 'bg-accent/10 font-semibold text-accent dark:bg-accent/20'
                  : 'text-muted hover:bg-surface-muted hover:text-ink dark:hover:bg-slate-800 dark:hover:text-slate-200'"
                @click="tab = key"
              >
                {{ t(`admin.${key}`) }}
              </button>
            </div>
          </div>
        </nav>

        <div class="min-w-0">
          <section v-if="tab === 'users'" class="space-y-3">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.usersHint') }}</p>
        <p v-if="usersError" class="alert alert-error" role="alert">
          {{ usersError }}
        </p>
        <input
          v-model="userSearch"
          :placeholder="t('admin.userSearch')"
          class="input max-w-md"
          @input="applyUserFilter"
        />
        <LoadingGrid v-if="usersLoading && !users.length" />
        <EmptyState v-else-if="!filteredUsers.length" :title="t('common.empty')" />
        <div v-else class="table-card">
          <table>
            <thead>
              <tr>
                <th>{{ t('admin.userAccount') }}</th>
                <th>{{ t('account.roles') }}</th>
                <th>{{ t('beeLevel.title') }}</th>
                <th>{{ t('admin.userStatus') }}</th>
                <th>{{ t('admin.userLastLogin') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredUsers"
                :key="row.userId"
                class="border-t border-line dark:border-slate-800"
              >
                <td>
                  <div class="font-medium">{{ row.displayName }}</div>
                  <code class="block text-xs text-muted">{{ row.email }}</code>
                </td>
                <td>
                  <span class="flex flex-wrap gap-1">
                    <Badge v-for="role in row.roles" :key="role" tone="muted">
                      {{ t(`role.${role}`) }}
                    </Badge>
                  </span>
                </td>
                <td>
                  <!-- The badge itself is the trigger: click to change the level
                       in place, no duplicate dropdown beside it. -->
                  <SelectMenu
                    :model-value="row.beeLevel"
                    :options="BEE_LEVELS.map((level) => ({ value: level, label: beeLevelLabel(level) }))"
                    :aria-label="t('admin.setBeeLevel')"
                    :disabled="savingUserId === row.userId"
                    @update:model-value="setUserBeeLevel(row, Number($event))"
                  >
                    <template #trigger>
                      <span class="inline-flex items-center gap-1 whitespace-nowrap">
                        <BeeLevelBadge :level="row.beeLevel" />
                        <span class="text-xs text-muted" aria-hidden="true">▾</span>
                      </span>
                    </template>
                  </SelectMenu>
                  <!-- A purchased membership riding above the granted level. -->
                  <span
                    v-if="row.effectiveBeeLevel > row.beeLevel"
                    class="mt-1 flex items-center gap-1 text-xs text-muted"
                    :title="formatDateTime(row.membershipExpiresAt)"
                  >
                    <BeeLevelBadge :level="row.effectiveBeeLevel" compact />
                    {{ t('admin.membershipUntil', { date: formatDate(row.membershipExpiresAt) }) }}
                  </span>
                </td>
                <td>
                  <button
                    class="btn btn-sm"
                    :class="row.status === 'ACTIVE' ? 'btn-danger-outline' : 'btn-success'"
                    :disabled="savingUserId === row.userId"
                    @click="toggleUserStatus(row)"
                  >
                    {{ row.status === 'ACTIVE' ? t('admin.disable') : t('admin.enable') }}
                  </button>
                </td>
                <td class="text-xs text-muted">{{ formatDateTime(row.lastLoginAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="tab === 'membership'" class="space-y-5">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.membershipHint') }}</p>
        <p v-if="membershipError" class="alert alert-error" role="alert">
          {{ membershipError }}
        </p>
        <LoadingGrid v-if="membershipLoading && !membershipPlans.length" />

        <template v-else>
          <div class="table-card">
            <table data-testid="membership-plans-table">
              <thead>
                <tr>
                  <th>{{ t('beeLevel.title') }}</th>
                  <th>{{ t('admin.membershipDuration') }}</th>
                  <th>{{ t('admin.membershipPrice') }}</th>
                  <th>{{ t('admin.membershipExternalUrl') }}</th>
                  <th>{{ t('admin.visibility') }}</th>
                  <th>{{ t('admin.membershipSort') }}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="plan in membershipPlans"
                  :key="plan.planId"
                  class="border-t border-line dark:border-slate-800"
                >
                  <td>
                    <SelectMenu
                      :model-value="plan.beeLevel"
                      :options="PURCHASABLE_LEVELS.map((level) => ({ value: level, label: beeLevelLabel(level) }))"
                      :aria-label="t('admin.setBeeLevel')"
                      :disabled="busyPlanId === plan.planId"
                      @update:model-value="updatePlan(plan, { beeLevel: Number($event) })"
                    >
                      <template #trigger>
                        <span class="inline-flex items-center gap-1 whitespace-nowrap">
                          <BeeLevelBadge :level="plan.beeLevel" />
                          <span class="text-xs text-muted" aria-hidden="true">▾</span>
                        </span>
                      </template>
                    </SelectMenu>
                  </td>
                  <td>
                    <label class="sr-only" :for="`duration-${plan.planId}`">{{ t('admin.membershipDuration') }}</label>
                    <input
                      :id="`duration-${plan.planId}`"
                      class="input w-24"
                      type="number"
                      min="1"
                      :value="plan.durationDays"
                      :disabled="busyPlanId === plan.planId"
                      @change="updatePlan(plan, { durationDays: Math.max(1, Number(($event.target as HTMLInputElement).value)) })"
                    />
                    <span class="ml-1 text-xs text-muted">{{ t('membership.daysUnit') }}</span>
                  </td>
                  <td>
                    <label class="sr-only" :for="`price-${plan.planId}`">{{ t('admin.membershipPrice') }}</label>
                    <input
                      :id="`price-${plan.planId}`"
                      class="input w-24"
                      type="text"
                      inputmode="decimal"
                      :value="(plan.priceFen / 100).toString()"
                      :disabled="busyPlanId === plan.planId"
                      @change="updatePlan(plan, { priceFen: Math.max(0, Math.round(Number(($event.target as HTMLInputElement).value || '0') * 100)) })"
                    />
                  </td>
                  <td>
                    <!-- Hosted-checkout link (Buy Me a Coffee Extra); blank = gateway checkout. -->
                    <label class="sr-only" :for="`url-${plan.planId}`">{{ t('admin.membershipExternalUrl') }}</label>
                    <input
                      :id="`url-${plan.planId}`"
                      class="input w-56 font-mono text-xs"
                      type="url"
                      :value="plan.externalUrl ?? ''"
                      :placeholder="t('admin.membershipExternalUrlPlaceholder')"
                      :disabled="busyPlanId === plan.planId"
                      @change="updatePlan(plan, { externalUrl: ($event.target as HTMLInputElement).value.trim() })"
                    />
                  </td>
                  <td>
                    <button
                      class="btn btn-sm"
                      :class="plan.active ? 'btn-danger-outline' : 'btn-success'"
                      :disabled="busyPlanId === plan.planId"
                      @click="updatePlan(plan, { active: !plan.active })"
                    >
                      {{ plan.active ? t('admin.delist') : t('admin.relist') }}
                    </button>
                  </td>
                  <td>
                    <label class="sr-only" :for="`sort-${plan.planId}`">{{ t('admin.membershipSort') }}</label>
                    <input
                      :id="`sort-${plan.planId}`"
                      class="input w-16"
                      type="number"
                      :value="plan.sort"
                      :disabled="busyPlanId === plan.planId"
                      @change="updatePlan(plan, { sort: Number(($event.target as HTMLInputElement).value) })"
                    />
                  </td>
                  <td>
                    <button
                      class="btn btn-sm btn-danger-outline"
                      :disabled="busyPlanId === plan.planId"
                      @click="removePlan(plan)"
                    >
                      {{ t('admin.dbDelete') }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Create row: yuan in, fen out. -->
          <form class="card grid gap-3 p-4 sm:grid-cols-6 sm:items-end" @submit.prevent="addPlan">
            <label class="block text-sm">
              {{ t('beeLevel.title') }}
              <SelectMenu
                v-model="newPlan.beeLevel"
                :options="PURCHASABLE_LEVELS.map((level) => ({ value: level, label: beeLevelLabel(level) }))"
                :aria-label="t('admin.setBeeLevel')"
                class="mt-1"
              >
                <template #trigger>
                  <span class="inline-flex items-center gap-1 whitespace-nowrap">
                    <BeeLevelBadge :level="newPlan.beeLevel" />
                    <span class="text-xs text-muted" aria-hidden="true">▾</span>
                  </span>
                </template>
              </SelectMenu>
            </label>
            <label class="block text-sm">
              {{ t('admin.membershipDuration') }}
              <input
                v-model.number="newPlan.durationDays"
                class="input mt-1"
                type="number"
                min="1"
                required
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.membershipPrice') }}（¥）
              <input v-model="newPlanYuan" class="input mt-1" inputmode="decimal" required />
            </label>
            <label class="block text-sm">
              {{ t('admin.membershipSort') }}
              <input v-model.number="newPlan.sort" class="input mt-1" type="number" />
            </label>
            <label class="block text-sm sm:col-span-2">
              {{ t('admin.membershipExternalUrl') }}
              <input
                v-model.trim="newPlan.externalUrl"
                class="input mt-1 font-mono text-xs"
                type="url"
                :placeholder="t('admin.membershipExternalUrlPlaceholder')"
              />
            </label>
            <button class="btn btn-primary sm:col-span-6" :disabled="membershipLoading">
              {{ t('admin.membershipAdd') }}
            </button>
          </form>

          <!-- Order stream -->
          <h3 class="pt-2 text-lg font-semibold">{{ t('admin.membershipOrders') }}</h3>
          <EmptyState v-if="!membershipOrders.length" :title="t('common.empty')" />
          <div v-else class="table-card">
            <table>
              <thead>
                <tr>
                  <th>{{ t('admin.membershipOrderNo') }}</th>
                  <th>{{ t('admin.userAccount') }}</th>
                  <th>{{ t('beeLevel.title') }}</th>
                  <th>{{ t('admin.membershipPrice') }}</th>
                  <th>{{ t('admin.membershipStatus') }}</th>
                  <th>{{ t('admin.membershipPaidAt') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="order in membershipOrders"
                  :key="order.orderNo"
                  class="border-t border-line dark:border-slate-800"
                >
                  <td><code class="text-xs">{{ order.orderNo }}</code></td>
                  <td>
                    <div class="font-medium">{{ order.displayName ?? '—' }}</div>
                    <code class="block text-xs text-muted">{{ order.email }}</code>
                  </td>
                  <td><BeeLevelBadge :level="order.targetLevel" /></td>
                  <td>{{ formatFen(order.priceFen ?? 0) }}</td>
                  <td>
                    <Badge :tone="membershipOrderTone(order.status ?? '')">{{ order.status }}</Badge>
                  </td>
                  <td class="text-xs text-muted">{{ formatDateTime(order.paidAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </section>

      <section v-if="tab === 'databases'" class="space-y-5">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.databasesHint') }}</p>
        <p v-if="databasesError" class="alert alert-error" role="alert">
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
                class="input mt-1"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.dbUser') }}
              <input
                v-model="newDb.username"
                required
                maxlength="200"
                placeholder="store"
                class="input mt-1"
              />
            </label>
            <label class="block text-sm sm:col-span-2">
              {{ t('admin.dbJdbcUrl') }}
              <input
                v-model="newDb.jdbcUrl"
                required
                maxlength="500"
                placeholder="jdbc:postgresql://db.example.com:5432/store"
                class="input mt-1 font-mono text-xs"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.dbPassword') }}
              <input
                v-model="newDb.password"
                required
                type="password"
                autocomplete="new-password"
                class="input mt-1"
              />
            </label>
            <div class="sm:self-end">
              <button :disabled="addingDb" class="btn btn-primary">
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
            class="card flex flex-wrap items-center justify-between gap-3 p-4"
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
                class="btn btn-secondary btn-sm"
                @click="probeDatabase(row)"
              >
                {{ t('admin.dbTest') }}
              </button>
              <button
                :disabled="dbBusyId === row.databaseId"
                class="btn btn-sm"
                :class="row.enabled ? 'btn-secondary' : 'btn-primary'"
                @click="toggleActivation(row)"
              >
                {{ row.enabled ? t('admin.dbDeactivate') : t('admin.dbActivate') }}
              </button>
              <button
                :disabled="dbBusyId === row.databaseId"
                class="btn btn-danger btn-sm"
                @click="removeDatabase(row)"
              >
                {{ t('admin.dbDelete') }}
              </button>
            </div>
          </li>
        </ul>

        <div v-if="lastProbe" class="card p-4 text-sm" role="status">
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
                class="input mt-1"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamUrl') }}
              <input
                v-model="newUrl"
                required
                type="url"
                placeholder="https://github.com/obra/superpowers"
                class="input mt-1"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamNamespace') }}
              <input
                v-model="newNamespace"
                required
                pattern="[a-z0-9][a-z0-9\-]{0,62}"
                placeholder="superpowers"
                class="input mt-1"
              />
            </label>
            <label class="block text-sm">
              {{ t('admin.upstreamAdapter') }}
              <SelectMenu
                v-model="newAdapter"
                class="mt-1"
                :options="[
                  { value: 'AUTO', label: 'AUTO' },
                  { value: 'CLAUDE_MARKETPLACE', label: 'CLAUDE_MARKETPLACE' },
                  { value: 'SKILL_REPOSITORY', label: 'SKILL_REPOSITORY' },
                  { value: 'MCP_REGISTRY', label: 'MCP_REGISTRY' },
                  { value: 'SKILLHUB_REGISTRY', label: 'SKILLHUB_REGISTRY' },
                ]"
                :aria-label="t('admin.upstreamAdapter')"
              />
            </label>
            <div class="sm:col-span-2">
              <button :disabled="adding" class="btn btn-primary">
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
            class="card p-4"
          >
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-semibold">{{ row.name }}</span>
                  <Badge tone="muted">{{ row.targetNamespace }}</Badge>
                  <Badge v-if="row.adapterType" tone="accent">{{ row.adapterType }}</Badge>
                </div>
                <code class="block truncate text-xs text-muted">{{ row.marketplaceUrl }}</code>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <button
                  class="btn btn-secondary btn-sm"
                  :disabled="logLoading && logUpstream?.upstreamId === row.upstreamId"
                  @click="openSyncLog(row)"
                >
                  {{ t('admin.viewLog') }}
                </button>
                <button
                  :disabled="syncingId === row.upstreamId || row.syncStatus === 'SYNCING'"
                  class="btn btn-primary btn-sm"
                  @click="syncNow(row)"
                >
                  {{ t('admin.syncNow') }}
                </button>
              </div>
            </div>
            <!-- Sync state lives under the upstream info: syncing / ok / failed,
                 with the details one click away instead of an inline error wall. -->
            <div class="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line pt-2.5 text-sm dark:border-slate-800">
              <template v-if="row.syncStatus === 'SYNCING'">
                <span class="inline-flex items-center gap-1.5 font-medium text-accent">
                  <span
                    class="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
                    aria-hidden="true"
                  />
                  {{ t('admin.syncSyncing') }}
                </span>
              </template>
              <template v-else-if="row.syncStatus === 'OK'">
                <span class="inline-flex items-center gap-1.5 font-medium text-emerald-600 dark:text-emerald-400">
                  <span aria-hidden="true">✓</span>
                  {{ t('admin.syncOk') }}
                </span>
                <span class="text-xs text-muted">
                  {{ t('admin.syncImported', { n: row.lastRunImported ?? 0 }) }} ·
                  {{ t('admin.syncSkipped', { n: row.lastRunSkipped ?? 0 }) }}
                </span>
              </template>
              <template v-else-if="row.syncStatus === 'FAILED'">
                <span class="inline-flex items-center gap-1.5 font-medium text-danger">
                  <span aria-hidden="true">✗</span>
                  {{ t('admin.syncFailed') }}
                </span>
                <span class="text-xs text-muted">
                  {{ t('admin.syncFailedCount', { n: row.lastRunFailed ?? 0 }) }}
                </span>
              </template>
              <template v-else>
                <span class="text-muted">{{ t('admin.syncPending') }}</span>
              </template>
              <span v-if="row.lastSyncAt" class="ml-auto text-xs text-muted">
                {{ formatDateTime(row.lastSyncAt) }}
              </span>
            </div>
          </li>
        </ul>

        <!-- Sync-log viewer: full per-run detail on demand, not inline on cards. -->
        <div
          v-if="logUpstream"
          class="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
          role="dialog"
          aria-modal="true"
          :aria-label="t('admin.syncLogTitle', { name: logUpstream.name })"
          @click.self="closeSyncLog"
        >
          <MagicCard class="max-h-[80vh] w-full max-w-2xl overflow-y-auto p-6">
            <div class="mb-4 flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h3 class="font-semibold">{{ t('admin.syncLogTitle', { name: logUpstream.name }) }}</h3>
                <code class="block truncate text-xs text-muted">{{ logUpstream.marketplaceUrl }}</code>
              </div>
              <button class="btn btn-secondary btn-sm shrink-0" @click="closeSyncLog">
                {{ t('common.close') }}
              </button>
            </div>
            <p v-if="logLoading" role="status" class="py-8 text-center text-muted">
              {{ t('common.loading') }}
            </p>
            <EmptyState v-else-if="!logRuns?.length" :title="t('admin.syncLogEmpty')" />
            <ol v-else class="space-y-3">
              <li
                v-for="run in logRuns"
                :key="run.runId"
                class="rounded-xl border border-line p-3 text-sm dark:border-slate-800"
              >
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <Badge :tone="runStatusTone(run.status)">{{ runStatusLabel(run.status) }}</Badge>
                  <span class="text-xs text-muted">
                    {{ formatDateTime(run.startedAt) }}
                    <template v-if="run.finishedAt"> → {{ formatDateTime(run.finishedAt) }}</template>
                  </span>
                  <span class="ml-auto text-xs text-muted">
                    {{ t('admin.syncImported', { n: run.imported }) }} ·
                    {{ t('admin.syncSkipped', { n: run.skipped }) }} ·
                    {{ t('admin.syncFailedCount', { n: run.failed }) }}
                  </span>
                </div>
                <pre
                  v-if="run.errors?.length"
                  class="mt-2 max-h-48 overflow-auto rounded-lg bg-surface-muted p-2.5 text-xs whitespace-pre-wrap break-all text-red-600 dark:bg-slate-900 dark:text-red-400"
                >{{ run.errors.join('\n') }}</pre>
              </li>
            </ol>
          </MagicCard>
        </div>
      </section>

      <section v-if="tab === 'listings'" class="space-y-3">
        <p class="text-sm text-muted dark:text-slate-400">{{ t('admin.listingsHint') }}</p>
        <div class="flex flex-wrap items-center gap-3">
          <input
            v-model="listingSearch"
            :placeholder="t('admin.listingSearch')"
            class="input max-w-md"
          />
          <span class="text-xs text-muted dark:text-slate-400">
            {{ t('admin.listingsCount', { n: filteredListings.length }) }}
          </span>
        </div>
        <LoadingGrid v-if="listingsLoading && !listings.length" />
        <EmptyState v-else-if="!filteredListings.length" :title="t('common.empty')" />
        <div v-else class="table-card">
          <table>
            <thead>
              <tr>
                <th>{{ t('admin.listingName') }}</th>
                <th>{{ t('common.type') }}</th>
                <th>{{ t('publisher.version') }}</th>
                <th>{{ t('admin.visibility') }}</th>
                <th>{{ t('admin.featured') }}</th>
                <th>{{ t('admin.minBeeLevel') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in filteredListings" :key="row.listingId">
                <td class="max-w-[15rem]">
                  <RouterLink :to="listingRoute(row.coordinate)" class="block truncate font-medium hover:text-accent">
                    {{ row.name }}
                  </RouterLink>
                  <code class="block truncate text-xs text-muted">{{ row.coordinate }}</code>
                </td>
                <td>{{ t(`type.${row.type}`) }}</td>
                <td class="whitespace-nowrap">{{ row.latestVersion ? 'v' + row.latestVersion : '—' }}</td>
                <td>
                  <div class="flex flex-col items-start gap-1">
                    <!-- State and action are separate: the button label alone
                         could not say what the listing currently IS. -->
                    <Badge :tone="row.visibility === 'PUBLIC' ? 'success' : 'danger'">
                      {{ row.visibility === 'PUBLIC' ? t('admin.visiblePublic') : t('admin.visibleUnlisted') }}
                    </Badge>
                    <button
                      class="btn btn-sm"
                      :class="row.visibility === 'PUBLIC' ? 'btn-secondary' : 'btn-success'"
                      @click="toggleVisibility(row)"
                    >
                      {{ row.visibility === 'PUBLIC' ? t('admin.delist') : t('admin.relist') }}
                    </button>
                  </div>
                </td>
                <td>
                  <button
                    class="btn btn-sm"
                    :class="row.featured ? 'bg-warning text-white' : 'btn-secondary'"
                    :aria-pressed="row.featured"
                    @click="toggleFeatured(row)"
                  >
                    {{ row.featured ? '★ ' + t('admin.featured') : t('admin.feature') }}
                  </button>
                </td>
                <td class="whitespace-nowrap">
                  <SelectMenu
                    :model-value="row.minBeeLevel"
                    :options="BEE_LEVELS.map((level) => ({ value: level, label: level === 0 ? t('admin.beeLevelPublic') : beeLevelLabel(level, '+') }))"
                    :aria-label="t('admin.minBeeLevel')"
                    @update:model-value="setListingLevel(row, Number($event))"
                  >
                    <template #trigger>
                      <span class="inline-flex items-center gap-1 whitespace-nowrap">
                        <Badge v-if="row.minBeeLevel === 0" tone="muted">{{ t('admin.beeLevelPublic') }}</Badge>
                        <BeeLevelBadge v-else :level="row.minBeeLevel" demands />
                        <span class="text-xs text-muted" aria-hidden="true">▾</span>
                      </span>
                    </template>
                  </SelectMenu>
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
          <p class="mt-2 text-xs text-muted">{{ t('admin.reportedAt') }}: {{ formatDateTime(report.createdAt) }}</p>
          <div class="mt-4 flex flex-col gap-2 sm:flex-row">
            <textarea
              v-model="notes[report.reportId ?? '']"
              :placeholder="t('admin.resolutionNote')"
              rows="2"
              class="input"
            />
            <div class="flex gap-2">
              <button class="btn btn-danger" @click="resolve(report, 'ACTIONED')">
                {{ t('admin.action') }}
              </button>
              <button class="btn btn-secondary" @click="resolve(report, 'DISMISSED')">
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
              <button type="button" class="btn btn-secondary" @click="appFileInput?.click()">
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
              <textarea v-model="appChangelog" rows="2" class="input mt-1" />
            </label>
            <button
              :disabled="appUploading || !appFile || !appDetected"
              class="btn btn-primary self-start whitespace-nowrap"
            >
              {{ appUploading ? t('admin.appUploading') : t('admin.appUploadAndPublish') }}
            </button>
          </form>
          <p v-if="appMessage" class="alert alert-success mt-2" role="status">{{ appMessage }}</p>
          <p v-if="appError" class="alert alert-error mt-2" role="alert">{{ appError }}</p>
        </MagicCard>

        <h3 class="font-semibold">{{ t('admin.appReleaseHistory') }}</h3>
        <EmptyState v-if="!appReleases.length" :title="t('admin.appReleaseEmpty')" />
        <ul v-else class="space-y-1 text-sm">
          <li
            v-for="rel in appReleases"
            :key="rel.releaseId"
            class="card flex flex-wrap items-center justify-between gap-2 px-3 py-2"
          >
            <div class="flex flex-wrap items-center gap-2">
              <span class="font-semibold">v{{ rel.version }}</span>
              <Badge tone="muted">{{ t(`channel.${rel.channel}`) }}</Badge>
              <StateChip :status="rel.status" />
              <span class="text-xs text-muted">
                {{ (rel.artifacts ?? []).map((a) => a.filename).join(', ') }}
              </span>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-xs text-muted">{{ formatDateTime(rel.publishedAt) }}</span>
              <button class="btn btn-danger-outline btn-sm" @click="deleteAppRelease(rel)">
                {{ t('admin.appDelete') }}
              </button>
            </div>
          </li>
        </ul>
      </section>

      <section v-if="tab === 'withdraw'" class="space-y-4">
        <p class="text-sm text-muted">{{ t('admin.withdrawHint') }}</p>
        <MagicCard class="p-6">
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="block text-sm">
              <span class="mb-1 block">{{ t('admin.withdrawPickListing') }}</span>
              <SelectMenu
                :model-value="withdrawListingId"
                :options="WITHDRAW_LISTING_OPTIONS"
                :aria-label="t('admin.withdrawPickListing')"
                @update:model-value="onWithdrawListingChange"
              />
            </label>
            <label class="block text-sm">
              <span class="mb-1 block">{{ t('admin.withdrawPickRelease') }}</span>
              <SelectMenu
                :model-value="releaseId"
                :options="WITHDRAW_RELEASE_OPTIONS"
                :aria-label="t('admin.withdrawPickRelease')"
                :disabled="!withdrawListingId"
                @update:model-value="onWithdrawReleaseChange"
              >
                <template #empty>
                  <span class="block px-3 py-2 text-xs text-muted">
                    {{ withdrawLoading ? t('common.loading') : t('admin.withdrawPickListingFirst') }}
                  </span>
                </template>
              </SelectMenu>
            </label>
          </div>
          <label class="mt-3 block text-sm">
            <span class="mb-1 block">{{ t('admin.reasonLabel') }}</span>
            <input v-model="reason" :placeholder="t('admin.reasonLabel')" class="input" />
          </label>
          <div class="mt-4 flex flex-wrap items-center gap-2">
            <button class="btn btn-secondary" :disabled="!releaseId" @click="withdraw('yank')">
              {{ t('admin.yank') }}
            </button>
            <button class="btn btn-danger" :disabled="!releaseId" @click="withdraw('quarantine')">
              {{ t('admin.quarantine') }}
            </button>
          </div>
          <p v-if="withdrawMessage" class="alert alert-success mt-3" role="status">{{ withdrawMessage }}</p>
          <p v-if="withdrawError" class="alert alert-error mt-3" role="alert">{{ withdrawError }}</p>
          <p class="mt-3 text-xs text-muted">{{ t('admin.quarantineHint') }}</p>
        </MagicCard>
      </section>

      <section v-if="tab === 'audit'">
        <EmptyState v-if="!auditEvents.length" :title="t('common.empty')" />
        <ul v-else class="space-y-1 text-xs">
          <li
            v-for="event in auditEvents"
            :key="event.eventId"
            class="card px-3 py-2"
          >
            <span class="text-muted">{{ formatDateTime(event.occurredAt) }}</span>
            · <span class="font-semibold">{{ event.action }}</span>
            · {{ event.resourceType }}/<code>{{ event.resourceId }}</code>
            · <span class="text-muted">{{ event.actorType }}:{{ event.actorId }}</span>
            <span v-if="event.afterSummary"> → {{ event.afterSummary }}</span>
          </li>
        </ul>
      </section>
        </div>
      </div>
    </template>
  </div>
</template>
