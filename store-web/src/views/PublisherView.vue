<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  api,
  ApiRequestError,
  type CatalogItem,
  type ListingDetail,
  type PublisherRelease,
  type SubmitResult,
  type UploadSession,
} from '../api/client';
import { Badge, MagicCard, ProgressBar } from '@infinia/magic-ui-vue';
import StateChip from '../components/StateChip.vue';
import SelectMenu from '../components/SelectMenu.vue';
import PageHeader from '../components/PageHeader.vue';
import { formatDate } from '../utils/format';
import { PluginManifestError, readPluginManifest, suggestSlug } from '../utils/pluginManifest';
import { usePublisherStore } from '../stores/publisher';

/**
 * Publisher center (design §8) as a focused release wizard: one centered card
 * holds the stepper and exactly one step panel — step 1 pick or create a
 * listing, step 2 create or resume a release, step 3 presigned upload →
 * submit. A step unlocks only when the previous one produced its artifact
 * (a selected listing / a current release), so the user can walk back but
 * never forward into an empty state; panels render with v-show so walking
 * back never loses form input. Status timeline reflects the state machine.
 */
const { t } = useI18n();
const store = usePublisherStore();
const message = ref('');
/** In-card feedback for the release pipeline — actions happen down here, so
 *  success/failure must be visible next to the buttons, not just at page top. */
const releaseMessage = ref('');

/** RFC 9457 problems carry stable codes; prefer the localized text. */
function problemText(e: unknown): string {
  if (e instanceof ApiRequestError && e.code) {
    const localized = t(`errors.${e.code}`);
    if (localized !== `errors.${e.code}`) return localized;
    return e.detail ?? e.message;
  }
  return e instanceof Error ? e.message : t('errors.server');
}

type StepNum = 1 | 2 | 3;
const step = ref<StepNum>(1);
const STEP_DEFS: { n: StepNum; label: string }[] = [
  { n: 1, label: 'publisher.step1' },
  { n: 2, label: 'publisher.step2' },
  { n: 3, label: 'publisher.step3' },
];

function stepReachable(n: StepNum): boolean {
  if (n === 1) return true;
  if (n === 2) return selectedListing.value != null;
  return currentRelease.value != null;
}

/** A step earns its checkmark once the next one has unlocked. */
function stepDone(n: StepNum): boolean {
  return n < 3 && stepReachable((n + 1) as StepNum);
}

function goToStep(n: StepNum) {
  if (!stepReachable(n)) return;
  step.value = n;
  // Coming back to step 1 re-checks every listing's release state, so the
  // groups reflect what just happened in steps 2-3 (submit, rejection…).
  if (n === 1) void hydrateListingStatuses();
}

function stepCircleClass(n: StepNum): string {
  if (step.value === n) return 'border-accent bg-accent text-white';
  if (stepDone(n)) return 'border-accent/30 bg-accent/10 text-accent';
  return 'border-line bg-surface text-muted dark:border-slate-700 dark:bg-slate-900';
}

function stepLabelClass(n: StepNum): string {
  if (step.value === n) return 'text-accent';
  if (stepReachable(n)) return 'text-ink dark:text-slate-200';
  return 'text-muted dark:text-slate-500';
}

/** 命名空间下拉:我所在组织保留的命名空间(= 组织标识)。 */
const orgNamespaces = ref<string[]>([]);
const CUSTOM_NAMESPACE = '__custom__';
const useCustomNamespace = ref(false);

const orgForm = ref({ slug: '', name: '' });
const listingForm = ref({
  namespace: '',
  slug: '',
  type: 'PLUGIN',
  name: '',
  summary: '',
  category: '',
  minBeeLevel: 0,
});
const releaseForm = ref({ version: '', channel: 'stable', requiresHost: '' });
const fileInput = ref<HTMLInputElement | null>(null);
const packageName = ref('');
const packageSize = ref(0);
const selectedListing = ref<CatalogItem | null>(null);
const selectedListingId = ref<string | null>(null);
const currentRelease = ref<PublisherRelease | null>(null);
const busy = ref(false);

/** The create-listing panel is on demand; a publisher with nothing to pick
 *  from lands directly on it so step 1 still offers a way forward. */
const showCreatePanel = ref(false);

/** Package import: read the plugin's manifest.json so the listing basics and
 *  the release version come from the package instead of hand-typing (the
 *  hand-typed version was exactly how plugin.version-mismatch happened). */
const packageInput = ref<HTMLInputElement | null>(null);
const importedFrom = ref<string | null>(null);

async function onImportPackage() {
  const file = packageInput.value?.files?.[0];
  if (!file) return;
  importedFrom.value = null;
  try {
    const manifest = await readPluginManifest(file);
    listingForm.value.type = 'PLUGIN';
    listingForm.value.slug = suggestSlug(manifest.id);
    listingForm.value.name = manifest.name;
    listingForm.value.summary = manifest.description;
    listingForm.value.category = manifest.category;
    releaseForm.value.version = manifest.version;
    if (manifest.engines) releaseForm.value.requiresHost = manifest.engines;
    importedFrom.value = `${manifest.id} · v${manifest.version}`;
    message.value = '';
    showCreatePanel.value = true;
  } catch (e) {
    message.value = e instanceof PluginManifestError
      ? t(`publisher.import.${e.message}`)
      : t('errors.server');
  } finally {
    if (packageInput.value) packageInput.value.value = '';
  }
}

/** Drag-and-drop mirrors the hidden file input (same pattern as step 3). */
function onImportDrop(event: DragEvent) {
  const files = event.dataTransfer?.files;
  if (files?.length && packageInput.value) {
    packageInput.value.files = files;
    onImportPackage();
  }
}

/**
 * Step-1 status board: every listing is bucketed by where its publishing flow
 * stands so published / in-flight / rejected listings don't pile up looking
 * identical. Buckets: needs-attention (draft, rejected, changes requested),
 * in review (in-review, scanning), published, no releases yet.
 */
type ListingBucket = 'action' | 'review' | 'published' | 'empty';
type ListingStatus = { listingId: string; bucket: ListingBucket; release: PublisherRelease | null };

const ACTION_STATES = ['DRAFT', 'REJECTED', 'CHANGES_REQUESTED'];
const listingStatuses = ref<Record<string, ListingStatus>>({});
const statusesReady = ref(false);
const hydrating = ref(false);

function summarizeListing(listingId: string, releases: PublisherRelease[]): ListingStatus {
  const byNewest = [...releases].sort((a, b) =>
    (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  const actionable = byNewest.find((r) => ACTION_STATES.includes(r.status));
  if (actionable) return { listingId, bucket: 'action', release: actionable };
  const inFlight = byNewest.find(
    (r) => r.status === 'IN_REVIEW' || r.status === 'SCANNING');
  if (inFlight) return { listingId, bucket: 'review', release: inFlight };
  const published = byNewest.find((r) => r.status === 'PUBLISHED');
  if (published) return { listingId, bucket: 'published', release: published };
  return { listingId, bucket: 'empty', release: null };
}

async function hydrateListingStatuses() {
  if (hydrating.value) return;
  hydrating.value = true;
  try {
    await Promise.all(store.listings.map(async (listing) => {
      try {
        const detail = await api.get<ListingDetail>(
          `/api/v1/listings/${listing.namespace}/${listing.slug}`);
        await store.loadReleases(detail.listingId);
        const releases = store.releasesByListing[detail.listingId] ?? [];
        listingStatuses.value[listing.coordinate] =
          summarizeListing(detail.listingId, releases);
      } catch {
        // Listing unavailable — without releases it lands in the trailing group.
        listingStatuses.value[listing.coordinate] = {
          listingId: '', bucket: 'empty', release: null,
        };
      }
    }));
    statusesReady.value = true;
  } finally {
    hydrating.value = false;
  }
}

const STATUS_GROUPS: { bucket: ListingBucket; titleKey: string }[] = [
  { bucket: 'action', titleKey: 'publisher.groupAction' },
  { bucket: 'review', titleKey: 'publisher.groupReview' },
  { bucket: 'published', titleKey: 'publisher.groupPublished' },
  { bucket: 'empty', titleKey: 'publisher.groupEmpty' },
];

const statusGroups = computed(() => STATUS_GROUPS
  .map((group) => ({
    ...group,
    items: store.listings
      .filter((listing) => listingStatuses.value[listing.coordinate]?.bucket === group.bucket)
      .map((listing) => ({
        listing,
        status: listingStatuses.value[listing.coordinate],
      })),
  }))
  .filter((group) => group.items.length > 0));

/**
 * The backend rework path (audit P1-3) accepts uploads for REJECTED and
 * CHANGES_REQUESTED releases and moves them back to DRAFT, so the upload
 * panel must light up for those states too — not just DRAFT.
 */
const UPLOADABLE_STATES = ['DRAFT', 'REJECTED', 'CHANGES_REQUESTED'];
const canUploadCurrent = computed(() =>
  currentRelease.value != null
  && UPLOADABLE_STATES.includes(currentRelease.value.status));

/** Published / withdrawn releases are history — they leave via withdraw (yank)
 *  or admin actions, not by publisher deletion. */
const NON_DELETABLE_STATES = ['PUBLISHED', 'DEPRECATED', 'YANKED', 'QUARANTINED'];
const canDeleteCurrent = computed(() =>
  currentRelease.value != null
  && !NON_DELETABLE_STATES.includes(currentRelease.value.status));

async function deleteCurrentRelease() {
  const release = currentRelease.value;
  if (!release) return;
  if (!window.confirm(t('publisher.deleteReleaseConfirm', { version: release.version }))) return;
  busy.value = true;
  try {
    await api.delete(`/api/v1/publisher/releases/${release.releaseId}`);
    message.value = t('publisher.releaseDeleted');
    releaseMessage.value = '';
    currentRelease.value = null;
    packageName.value = '';
    packageSize.value = 0;
    if (fileInput.value) fileInput.value.value = '';
    if (selectedListingId.value) {
      await store.loadReleases(selectedListingId.value).catch(() => {});
    }
    void hydrateListingStatuses();
    goToStep(2);
  } catch (e) {
    releaseMessage.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/** infinia://plugin/ns/slug → PLUGIN — badges listing cards without extra API data. */
function typeFromCoordinate(coordinate: string): string {
  return (coordinate.replace(/^infinia:\/\//, '').split('/')[0] ?? '').toUpperCase();
}

async function createOrg() {
  busy.value = true;
  try {
    const created = await api.post<{ slug: string }>('/api/v1/organizations', orgForm.value);
    message.value = t('publisher.orgCreated');
    await loadOrgNamespaces();
    listingForm.value.namespace = created?.slug ?? orgForm.value.slug;
    useCustomNamespace.value = false;
    orgForm.value = { slug: '', name: '' };
  } catch (e) {
    message.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

async function createListing() {
  busy.value = true;
  try {
    await api.post('/api/v1/publisher/listings', {
      ...listingForm.value,
      tags: [],
    });
    // Light selection: a fresh listing has no releases, so skip the releases
    // fetch and walk into step 2 — creating a release there resolves the
    // listing UUID on demand.
    selectedListing.value = {
      coordinate: `infinia://${listingForm.value.type.toLowerCase()}/${listingForm.value.namespace}/${listingForm.value.slug}`,
      name: listingForm.value.name,
    } as CatalogItem;
    selectedListingId.value = null;
    currentRelease.value = null;
    step.value = 2;
    showCreatePanel.value = false;
    await store.load();
    message.value = t('publisher.listingCreated');
  } catch (e) {
    message.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

/**
 * infinia://app/official/fengyu-host → { type: 'app', namespace: 'official',
 * slug: 'fengyu-host' }. The protocol separator leaves an empty first path
 * segment after the scheme, so split('/') must skip it before destructuring.
 */
function splitCoordinate(coordinate: string): { type: string; namespace: string; slug: string } {
  const [type = '', namespace = '', slug = ''] = coordinate.replace(/^infinia:\/\//, '').split('/');
  return { type, namespace, slug };
}

async function createRelease() {
  const selected = selectedListing.value;
  if (!selected?.coordinate) return;
  busy.value = true;
  message.value = '';
  releaseMessage.value = '';
  try {
    // Resolve the listing UUID from the public detail endpoint.
    const { namespace, slug } = splitCoordinate(selected.coordinate);
    const detail = await api.get<ListingDetail>(
      `/api/v1/listings/${namespace}/${slug}`,
    );
    const release = await api.post<PublisherRelease>(
      `/api/v1/publisher/listings/${detail.listingId}/releases`,
      releaseForm.value,
    );
    selectedListingId.value = detail.listingId;
    currentRelease.value = release;
    packageName.value = '';
    packageSize.value = 0;
    if (fileInput.value) fileInput.value.value = '';
    await store.loadReleases(detail.listingId);
    releaseMessage.value = t('publisher.releaseCreated');
    step.value = 3;
  } catch (e) {
    releaseMessage.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

function onPackageChange() {
  const file = fileInput.value?.files?.[0];
  packageName.value = file?.name ?? '';
  packageSize.value = file?.size ?? 0;
}

/** Drag-and-drop mirrors the hidden file input, so both paths share one state. */
function onPackageDrop(event: DragEvent) {
  const files = event.dataTransfer?.files;
  if (files?.length && fileInput.value) {
    fileInput.value.files = files;
    onPackageChange();
  }
}

async function uploadAndSubmit() {
  const file = fileInput.value?.files?.[0];
  const release = currentRelease.value;
  if (!file || !release) return;
  busy.value = true;
  releaseMessage.value = '';
  try {
    const session = await api.post<UploadSession>(
      `/api/v1/publisher/releases/${release.releaseId}/uploads`,
      { filename: file.name },
    );
    await api.putRaw(session.uploadUrl, await file.arrayBuffer());
    const result = await api.post<SubmitResult>(
      `/api/v1/publisher/releases/${release.releaseId}/submit`,
    );
    await pollStatus();
    const finalStatus = store.releases[release.releaseId]?.status ?? result.status;
    releaseMessage.value = t('publisher.submittedStatus', {
      status: t(`state.${finalStatus}`),
    });
  } catch (e) {
    releaseMessage.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

async function pollStatus() {
  const releaseId = currentRelease.value?.releaseId;
  if (!releaseId) return;
  for (let i = 0; i < 20; i++) {
    await store.refreshRelease(releaseId);
    const status = store.releases[releaseId]?.status;
    if (status !== 'SCANNING' && status !== 'UPLOADING') {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  currentRelease.value = store.releases[releaseId] ?? currentRelease.value;
}

async function selectListing(listing: CatalogItem) {
  selectedListing.value = listing;
  selectedListingId.value = null;
  currentRelease.value = null;
  message.value = '';
  releaseMessage.value = '';
  packageName.value = '';
  packageSize.value = 0;
  // Resolve the listing UUID, then load its releases (incl. DRAFTs) so an
  // interrupted draft can be resumed — the upload area keys off currentRelease.
  try {
    const { namespace, slug } = splitCoordinate(listing.coordinate);
    const detail = await api.get<ListingDetail>(
      `/api/v1/listings/${namespace}/${slug}`,
    );
    selectedListingId.value = detail.listingId;
    await store.loadReleases(detail.listingId);
    const draft = store.releasesByListing[detail.listingId]?.find(
      (r) => r.status === 'DRAFT',
    );
    if (draft) {
      currentRelease.value = draft;
      releaseMessage.value = t('publisher.draftResumed');
      // "继续草稿" is the point of picking this listing — land directly on
      // the upload step instead of making the user click the draft again.
      step.value = 3;
    } else {
      step.value = 2;
    }
  } catch {
    /* detail load failure leaves the wizard usable for new releases */
    message.value = t('publisher.releasesUnavailable');
    step.value = 2;
  }
}

/**
 * Any release row can be selected: DRAFT to resume the upload, REJECTED /
 * CHANGES_REQUESTED to read the scan findings and re-upload (the backend
 * flips those back to DRAFT on upload). Terminal states show their history.
 */
async function pickRelease(release: PublisherRelease) {
  message.value = '';
  releaseMessage.value = '';
  packageName.value = '';
  packageSize.value = 0;
  if (fileInput.value) fileInput.value.value = '';
  currentRelease.value = release;
  step.value = 3;
  // The listing-scoped list omits scan findings; the single-release detail
  // carries them, so a rejected row can explain itself after a reload.
  if (release.findings?.length) return;
  try {
    await store.refreshRelease(release.releaseId);
    currentRelease.value = store.releases[release.releaseId] ?? release;
  } catch {
    /* keep the row-level data if the detail fetch fails */
  }
}

async function loadOrgNamespaces() {
  try {
    const orgs = await api.get<{ slug: string }[]>('/api/v1/organizations');
    orgNamespaces.value = orgs.map((o) => o.slug);
    if (!listingForm.value.namespace && orgNamespaces.value.length) {
      listingForm.value.namespace = orgNamespaces.value[0];
    }
  } catch {
    /* 下拉加载失败时回退手动输入 */
  }
}

function onNamespaceChange(value: string) {
  if (value === CUSTOM_NAMESPACE) {
    useCustomNamespace.value = true;
    listingForm.value.namespace = '';
  } else {
    useCustomNamespace.value = false;
    listingForm.value.namespace = value;
  }
}

onMounted(async () => {
  loadOrgNamespaces();
  await store.load();
  showCreatePanel.value = !store.listings.length;
  void hydrateListingStatuses();
});

const LISTING_TYPES = ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW'] as const;
const BEE_LEVEL_OPTIONS = [0, 1, 2, 3, 4].map((level) => ({
  value: level,
  label:
    level === 0
      ? t('publisher.beeLevelPublic')
      : `${t(`beeLevel.${level}`)} · Lv${level}+`,
}));
const CHANNEL_OPTIONS = [
  { value: 'stable', label: t('channel.stable') },
  { value: 'beta', label: t('channel.beta') },
];

/** Row hint on the right edge of each release row. */
function releaseRowHint(status: string): string {
  if (status === 'DRAFT') return t('publisher.clickToResume');
  if (status === 'REJECTED' || status === 'CHANGES_REQUESTED') {
    return t('publisher.clickToInspect');
  }
  return '';
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl space-y-6">
    <PageHeader :title="t('publisher.title')" :subtitle="t('publisher.subtitle')" />
    <p v-if="message" class="alert alert-info" role="status">
      {{ message }}
    </p>

    <MagicCard>
      <!-- 步骤指示器:圆点 + 连线,只有已解锁的步骤可以点击回退。 -->
      <ol
        class="flex items-center gap-1 border-b border-line px-6 py-4 dark:border-slate-800"
        :aria-label="t('publisher.stepNav')"
      >
        <template v-for="(def, index) in STEP_DEFS" :key="def.n">
          <li>
            <button
              type="button"
              class="tap-compact flex items-center gap-2.5 rounded-lg py-1 pr-1 text-left disabled:cursor-not-allowed"
              :disabled="!stepReachable(def.n)"
              :aria-current="step === def.n ? 'step' : undefined"
              @click="goToStep(def.n)"
            >
              <span
                class="grid size-7 shrink-0 place-items-center rounded-full border text-xs font-semibold transition-colors"
                :class="stepCircleClass(def.n)"
              >
                <svg
                  v-if="stepDone(def.n)"
                  viewBox="0 0 16 16"
                  class="size-3.5"
                  fill="none"
                  aria-hidden="true"
                >
                  <path d="M3 8.5 6.5 12 13 4.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <template v-else>{{ def.n }}</template>
              </span>
              <span class="text-sm font-medium" :class="stepLabelClass(def.n)">
                {{ t(def.label) }}
              </span>
            </button>
          </li>
          <li
            v-if="index < STEP_DEFS.length - 1"
            aria-hidden="true"
            class="mx-2 h-px flex-1"
            :class="stepDone(def.n) ? 'bg-accent/40' : 'bg-line dark:bg-slate-800'"
          />
        </template>
      </ol>

      <div class="p-6">
        <!-- 第 1 步:选择或创建商品 -->
        <section v-show="step === 1">
          <header class="mb-4">
            <h2 class="text-base font-semibold">{{ t('publisher.step1Title') }}</h2>
            <p class="mt-1 text-sm text-muted dark:text-slate-400">{{ t('publisher.step1Hint') }}</p>
          </header>

          <!-- 状态分组:发布成功 / 流程中 / 需要处理 / 暂无版本一目了然;
               加载完成前退回平铺列表。 -->
          <template v-if="statusesReady">
            <div v-for="group in statusGroups" :key="group.bucket" class="mb-5 last:mb-0">
              <h3 class="mb-2 text-xs font-semibold uppercase tracking-wide text-muted dark:text-slate-400">
                {{ t(group.titleKey) }} ({{ group.items.length }})
              </h3>
              <ul class="grid gap-2.5 sm:grid-cols-2">
                <li v-for="{ listing, status } in group.items" :key="listing.coordinate">
                  <button
                    type="button"
                    class="w-full rounded-lg border p-3.5 text-left transition-colors"
                    :class="selectedListing?.coordinate === listing.coordinate
                      ? 'border-accent ring-1 ring-accent'
                      : 'border-line hover:border-accent/50 dark:border-slate-800'"
                    @click="selectListing(listing)"
                  >
                    <span class="flex items-center gap-2">
                      <span class="min-w-0 flex-1 truncate font-medium">{{ listing.name }}</span>
                      <Badge tone="muted">{{ typeFromCoordinate(listing.coordinate) }}</Badge>
                    </span>
                    <span v-if="status?.release" class="mt-1.5 flex items-center gap-2">
                      <StateChip :status="status.release.status" />
                      <code class="text-xs text-muted dark:text-slate-400">v{{ status.release.version }}</code>
                    </span>
                    <code class="mt-1 block truncate text-xs text-muted dark:text-slate-400">{{ listing.coordinate }}</code>
                  </button>
                </li>
              </ul>
            </div>
          </template>
          <ul v-else class="grid gap-2.5 sm:grid-cols-2">
            <li v-for="listing in store.listings" :key="listing.coordinate">
              <button
                type="button"
                class="w-full rounded-lg border p-3.5 text-left transition-colors"
                :class="selectedListing?.coordinate === listing.coordinate
                  ? 'border-accent ring-1 ring-accent'
                  : 'border-line hover:border-accent/50 dark:border-slate-800'"
                @click="selectListing(listing)"
              >
                <span class="flex items-center gap-2">
                  <span class="min-w-0 flex-1 truncate font-medium">{{ listing.name }}</span>
                  <Badge tone="muted">{{ typeFromCoordinate(listing.coordinate) }}</Badge>
                </span>
                <code class="mt-1 block truncate text-xs text-muted dark:text-slate-400">{{ listing.coordinate }}</code>
              </button>
            </li>
          </ul>
          <ul class="mt-2.5 grid gap-2.5 sm:grid-cols-2">
            <li>
              <button
                type="button"
                class="flex h-full min-h-24 w-full flex-col items-center justify-center gap-1.5 rounded-lg border-2 border-dashed p-4 text-sm font-medium transition-colors"
                :class="showCreatePanel
                  ? 'border-accent/70 text-accent'
                  : 'border-line text-muted hover:border-accent/60 hover:text-accent dark:border-slate-700'"
                :aria-expanded="showCreatePanel"
                @click="showCreatePanel = !showCreatePanel"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
                </svg>
                {{ t('publisher.createNewListing') }}
              </button>
            </li>
          </ul>

          <div v-show="showCreatePanel" class="mt-6 border-t border-line pt-5 dark:border-slate-800">
            <!-- 插件包导入:基础信息与版本号从 manifest.json 读取,不必手工填写。 -->
            <input ref="packageInput" type="file" accept=".fyp,.zip" class="hidden" @change="onImportPackage" />
            <button
              type="button"
              class="flex w-full flex-col items-center gap-1 rounded-xl border-2 border-dashed border-line px-6 py-5 text-center transition-colors hover:border-accent/60 hover:bg-accent/5 dark:border-slate-700"
              @click="packageInput?.click()"
              @dragover.prevent
              @drop.prevent="onImportDrop"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true" class="text-muted dark:text-slate-400">
                <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              </svg>
              <span class="text-sm font-medium">{{ t('publisher.importHint') }}</span>
              <span class="text-xs text-muted dark:text-slate-400">{{ t('publisher.importHintDetail') }}</span>
            </button>
            <p v-if="importedFrom" class="mt-2 text-xs text-accent" role="status">
              {{ t('publisher.importDone', { source: importedFrom }) }}
            </p>

            <!-- 组织表单只在还没有命名空间时出现;创建后自动预选命名空间。 -->
            <div v-if="!orgNamespaces.length" class="mb-5 rounded-lg bg-surface-muted p-4 dark:bg-slate-900/60">
              <h3 class="text-sm font-semibold">{{ t('publisher.createOrg') }}</h3>
              <p class="mt-0.5 text-xs text-muted dark:text-slate-400">{{ t('publisher.namespaceNone') }}</p>
              <form class="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]" @submit.prevent="createOrg">
                <label class="block text-sm">
                  <span class="mb-1 block">{{ t('publisher.orgSlug') }}</span>
                  <input v-model="orgForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" class="input" />
                </label>
                <label class="block text-sm">
                  <span class="mb-1 block">{{ t('publisher.orgName') }}</span>
                  <input v-model="orgForm.name" class="input" />
                </label>
                <button type="submit" :disabled="busy" class="btn btn-primary self-end whitespace-nowrap">{{ t('publisher.createOrgAction') }}</button>
              </form>
            </div>

            <form class="grid gap-4 sm:grid-cols-2" @submit.prevent="createListing">
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.namespace') }}</span>
                <SelectMenu
                  v-if="!useCustomNamespace"
                  :model-value="listingForm.namespace"
                  :options="[
                    ...orgNamespaces.map((ns) => ({ value: ns, label: ns })),
                    { value: CUSTOM_NAMESPACE, label: t('publisher.namespaceCustom') },
                  ]"
                  :aria-label="t('publisher.namespace')"
                  @update:model-value="onNamespaceChange(String($event))"
                />
                <span v-else class="flex gap-2">
                  <input
                    v-model="listingForm.namespace"
                    required
                    :placeholder="t('publisher.namespace')"
                    class="input min-w-0 flex-1"
                  />
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm tap-compact mt-0.5 shrink-0 self-start whitespace-nowrap"
                    @click="useCustomNamespace = false"
                  >
                    {{ t('publisher.namespaceBackToList') }}
                  </button>
                </span>
              </label>
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('common.type') }}</span>
                <SelectMenu
                  v-model="listingForm.type"
                  :options="LISTING_TYPES.map((type) => ({ value: type, label: t(`type.${type}`) }))"
                  :aria-label="t('common.type')"
                />
              </label>
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.slug') }}</span>
                <input v-model="listingForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" class="input" />
              </label>
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.name') }}</span>
                <input v-model="listingForm.name" required class="input" />
              </label>
              <label class="block text-sm sm:col-span-2">
                <span class="mb-1 block">{{ t('publisher.summary') }}</span>
                <input v-model="listingForm.summary" class="input" />
              </label>
              <div class="text-sm sm:col-span-2">
                <span class="mb-1 block">{{ t('publisher.minBeeLevel') }}</span>
                <SelectMenu
                  :model-value="listingForm.minBeeLevel"
                  :options="BEE_LEVEL_OPTIONS"
                  :aria-label="t('publisher.minBeeLevel')"
                  @update:model-value="listingForm.minBeeLevel = Number($event)"
                />
                <p class="mt-1 text-xs text-muted dark:text-slate-400">{{ t('publisher.minBeeLevelHint') }}</p>
              </div>
              <div class="flex justify-end sm:col-span-2">
                <button type="submit" :disabled="busy" class="btn btn-primary whitespace-nowrap">{{ t('publisher.createListingAction') }}</button>
              </div>
            </form>
          </div>
        </section>

        <!-- 第 2 步:新建版本,或点击草稿/被退回的版本继续 -->
        <section v-show="step === 2">
          <header class="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
            <div class="min-w-0">
              <h2 class="text-base font-semibold">{{ t('publisher.newRelease') }}</h2>
              <p v-if="selectedListing" class="mt-1 truncate text-sm text-muted dark:text-slate-400">{{ selectedListing.name }}</p>
            </div>
            <button type="button" class="btn btn-ghost btn-sm tap-compact shrink-0" @click="goToStep(1)">
              ← {{ t('publisher.changeListing') }}
            </button>
          </header>

          <div v-if="selectedListingId">
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-muted dark:text-slate-400">{{ t('publisher.releases') }}</h3>
            <p v-if="!store.releasesByListing[selectedListingId]?.length" class="py-2 text-sm text-muted dark:text-slate-400">
              {{ t('publisher.noReleases') }}
            </p>
            <ul v-else class="divide-y divide-line dark:divide-slate-800">
              <li
                v-for="release in store.releasesByListing[selectedListingId]"
                :key="release.releaseId"
              >
                <button
                  type="button"
                  class="tap-compact -mx-2 flex w-full flex-wrap items-center gap-3 rounded-lg px-2 py-2.5 text-left text-sm transition-colors hover:bg-surface-muted/70 dark:hover:bg-slate-800/60"
                  :class="currentRelease?.releaseId === release.releaseId ? 'text-accent' : ''"
                  @click="pickRelease(release)"
                >
                  <code class="font-semibold">v{{ release.version }}</code>
                  <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
                  <StateChip :status="release.status" />
                  <span class="ml-auto text-xs text-muted dark:text-slate-400">
                    {{ releaseRowHint(release.status) || formatDate(release.createdAt) }}
                  </span>
                </button>
              </li>
            </ul>
          </div>

          <form class="mt-5 border-t border-line pt-4 dark:border-slate-800" @submit.prevent="createRelease">
            <div class="grid gap-4 sm:grid-cols-3">
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.version') }}</span>
                <input v-model="releaseForm.version" required placeholder="1.0.0" class="input" />
              </label>
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.channel') }}</span>
                <SelectMenu v-model="releaseForm.channel" :options="CHANNEL_OPTIONS" :aria-label="t('publisher.channel')" />
              </label>
              <label class="block text-sm">
                <span class="mb-1 block">{{ t('publisher.requiresHost') }}</span>
                <input v-model="releaseForm.requiresHost" placeholder=">=4.0.0 <5.0.0" class="input" />
              </label>
            </div>
            <div class="mt-4 flex justify-end">
              <button type="submit" :disabled="busy" class="btn btn-primary whitespace-nowrap">{{ t('publisher.createReleaseAction') }}</button>
            </div>
          </form>
        </section>

        <!-- 第 3 步:上传安装包并提交审核 -->
        <section v-show="step === 3">
          <header class="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
            <div class="min-w-0">
              <h2 class="text-base font-semibold">{{ t('publisher.uploadPackage') }}</h2>
              <p class="mt-1 truncate text-sm text-muted dark:text-slate-400">{{ selectedListing?.name }}</p>
            </div>
            <button type="button" class="btn btn-ghost btn-sm tap-compact shrink-0" @click="goToStep(2)">
              ← {{ t('publisher.backToReleases') }}
            </button>
          </header>

          <div v-if="currentRelease">
            <div class="mb-4 flex flex-wrap items-center gap-2">
              <StateChip :status="currentRelease.status" />
              <Badge tone="muted">v{{ currentRelease.version }}</Badge>
              <Badge tone="muted">{{ t(`channel.${currentRelease.channel}`) }}</Badge>
              <button
                v-if="canDeleteCurrent"
                type="button"
                class="btn btn-danger-outline btn-sm tap-compact ml-auto"
                :disabled="busy"
                @click="deleteCurrentRelease"
              >
                {{ t('publisher.deleteRelease') }}
              </button>
            </div>

            <p v-if="releaseMessage" class="alert alert-info mb-4" role="status">
              {{ releaseMessage }}
            </p>

            <div v-if="currentRelease.status === 'SCANNING'" class="space-y-2">
              <ProgressBar />
              <p class="text-xs text-muted dark:text-slate-400">{{ t('publisher.scanningHint') }}</p>
            </div>

            <div v-if="canUploadCurrent" class="space-y-3">
              <p v-if="currentRelease.status !== 'DRAFT'" class="alert alert-info">
                {{ t('publisher.reworkHint') }}
              </p>
              <!-- Same hidden-input + styled picker pattern as the admin app-release
                   upload, so both package uploaders render identically. -->
              <input ref="fileInput" type="file" class="hidden" @change="onPackageChange" />
              <button
                type="button"
                class="flex w-full flex-col items-center gap-2 rounded-xl border-2 border-dashed border-line px-6 py-8 text-center transition-colors hover:border-accent/60 hover:bg-accent/5 dark:border-slate-700"
                @click="fileInput?.click()"
                @dragover.prevent
                @drop.prevent="onPackageDrop"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true" class="text-muted dark:text-slate-400">
                  <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
                </svg>
                <span class="text-sm font-medium">{{ t('publisher.uploadDropHint') }}</span>
                <span class="text-xs text-muted dark:text-slate-400">{{ t('publisher.uploadHint') }}</span>
              </button>
              <div class="flex flex-wrap items-center gap-3">
                <span class="min-w-0 flex-1 truncate text-sm" :class="packageName ? '' : 'text-muted dark:text-slate-400'">
                  <template v-if="packageName">{{ packageName }} <span class="text-xs">({{ formatSize(packageSize) }})</span></template>
                  <template v-else>—</template>
                </span>
                <button :disabled="busy || !packageName" class="btn btn-primary whitespace-nowrap" @click="uploadAndSubmit">
                  {{ t('publisher.submit') }}
                </button>
              </div>
            </div>

            <ul v-if="currentRelease.findings?.length" class="mt-4 space-y-1 text-sm">
              <li v-for="finding in currentRelease.findings" :key="finding.rule" class="card p-2">
                <Badge :tone="finding.severity === 'ERROR' || finding.severity === 'CRITICAL' ? 'danger' : 'muted'">
                  {{ finding.severity }}
                </Badge>
                {{ finding.rule }} — {{ finding.message }}
              </li>
            </ul>
          </div>
        </section>
      </div>
    </MagicCard>
  </div>
</template>
