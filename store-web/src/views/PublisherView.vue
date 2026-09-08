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
import EmptyState from '../components/EmptyState.vue';
import { formatDate } from '../utils/format';
import { usePublisherStore } from '../stores/publisher';
import { useAuthStore } from '../stores/auth';

/**
 * Publisher center (design §8): create org/namespace → listing → release →
 * presigned upload → submit. Status timeline reflects the state machine.
 */
const { t } = useI18n();
const store = usePublisherStore();
const auth = useAuthStore();
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

/**
 * The backend rework path (audit P1-3) accepts uploads for REJECTED and
 * CHANGES_REQUESTED releases and moves them back to DRAFT, so the upload
 * panel must light up for those states too — not just DRAFT.
 */
const UPLOADABLE_STATES = ['DRAFT', 'REJECTED', 'CHANGES_REQUESTED'];
const canUploadCurrent = computed(() =>
  currentRelease.value != null
  && UPLOADABLE_STATES.includes(currentRelease.value.status));

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
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
    message.value = t('publisher.listingCreated');
    selectedListing.value = {
      coordinate: `infinia://${listingForm.value.type.toLowerCase()}/${listingForm.value.namespace}/${listingForm.value.slug}`,
      name: listingForm.value.name,
    } as CatalogItem;
    await store.load();
  } catch (e) {
    message.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

/** Publisher-side Infinia Level gate adjustment (Infinia Level 门槛). */
const gateCoordinate = ref('');
const gateLevel = ref(0);

async function applyGate() {
  if (!gateCoordinate.value) return;
  busy.value = true;
  try {
    // Resolve the listing UUID from the public detail endpoint — the publisher
    // picks a listing by name, never by pasting a raw UUID.
    const { namespace, slug } = splitCoordinate(gateCoordinate.value);
    const detail = await api.get<ListingDetail>(`/api/v1/listings/${namespace}/${slug}`);
    await api.post(
      `/api/v1/publisher/listings/${detail.listingId}/min-bee-level`,
      { minBeeLevel: gateLevel.value },
    );
    message.value = t('publisher.gateUpdated');
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
    }
  } catch {
    /* detail load failure leaves the wizard usable for new releases */
    message.value = t('publisher.releasesUnavailable');
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

onMounted(() => {
  store.load();
  loadOrgNamespaces();
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
const GATE_LISTING_OPTIONS = computed(() =>
  store.listings.map((listing) => ({
    value: listing.coordinate,
    label: listing.name,
  })));

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
  <div class="space-y-8">
    <PageHeader :title="t('publisher.title')" :subtitle="t('publisher.steps')" />
    <p v-if="message" class="alert alert-info" role="status">
      {{ message }}
    </p>

    <section>
      <h2 class="mb-3 text-lg font-semibold">{{ t('publisher.listings') }}</h2>
      <EmptyState v-if="!store.listings.length" :title="t('common.empty')" />
      <ul v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <li
          v-for="listing in store.listings"
          :key="listing.coordinate"
          class="card cursor-pointer p-4 text-sm transition-colors"
          :class="selectedListing?.coordinate === listing.coordinate
            ? 'border-accent ring-1 ring-accent'
            : 'hover:border-muted/40'"
          @click="selectListing(listing)"
        >
          <div class="font-medium">{{ listing.name }}</div>
          <code class="text-xs text-muted">{{ listing.coordinate }}</code>
        </li>
      </ul>
    </section>

    <MagicCard class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.createOrg') }}</h2>
      <form class="grid gap-3 sm:grid-cols-3" @submit.prevent="createOrg">
        <input v-model="orgForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" :placeholder="t('publisher.orgSlug')" class="input" />
        <input v-model="orgForm.name" :placeholder="t('publisher.orgName')" class="input" />
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">{{ t('publisher.createOrgAction') }}</button>
      </form>
    </MagicCard>

    <MagicCard class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.newListings') }}</h2>
      <form class="grid gap-3 sm:grid-cols-3" @submit.prevent="createListing">
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
        <input
          v-else
          v-model="listingForm.namespace"
          required
          :placeholder="t('publisher.namespace')"
          class="input"
        />
        <button
          v-if="useCustomNamespace"
          type="button"
          class="btn btn-secondary shrink-0 whitespace-nowrap"
          @click="useCustomNamespace = false"
        >
          {{ t('publisher.namespaceBackToList') }}
        </button>
        <p v-if="!orgNamespaces.length && !useCustomNamespace" class="text-xs text-muted sm:col-span-3 dark:text-slate-400">
          {{ t('publisher.namespaceNone') }}
        </p>
        <input v-model="listingForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" :placeholder="t('publisher.slug')" class="input" />
        <SelectMenu
          v-model="listingForm.type"
          :options="LISTING_TYPES.map((type) => ({ value: type, label: t(`type.${type}`) }))"
          :aria-label="t('common.type')"
        />
        <input v-model="listingForm.name" required :placeholder="t('publisher.name')" class="input" />
        <input v-model="listingForm.summary" :placeholder="t('publisher.summary')" class="input sm:col-span-2" />
        <label class="block text-sm sm:col-span-2">
          {{ t('publisher.minBeeLevel') }}
          <SelectMenu
            :model-value="listingForm.minBeeLevel"
            class="mt-1"
            :options="BEE_LEVEL_OPTIONS"
            :aria-label="t('publisher.minBeeLevel')"
            @update:model-value="listingForm.minBeeLevel = Number($event)"
          />
          <span class="mt-1 block text-xs text-muted">{{ t('publisher.minBeeLevelHint') }}</span>
        </label>
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">{{ t('publisher.createListingAction') }}</button>
      </form>
    </MagicCard>

    <MagicCard class="p-6">
      <h2 class="mb-2 font-semibold">{{ t('publisher.setGate') }}</h2>
      <p class="mb-3 text-sm text-muted">{{ t('publisher.setGateHint') }}</p>
      <form class="grid gap-3 sm:grid-cols-3" @submit.prevent="applyGate">
        <SelectMenu
          :model-value="gateCoordinate"
          :options="GATE_LISTING_OPTIONS"
          :aria-label="t('publisher.setGatePickListing')"
          @update:model-value="gateCoordinate = String($event)"
        />
        <SelectMenu
          :model-value="gateLevel"
          :options="BEE_LEVEL_OPTIONS"
          :aria-label="t('publisher.minBeeLevel')"
          @update:model-value="gateLevel = Number($event)"
        />
        <button type="submit" :disabled="busy || !gateCoordinate" class="btn btn-primary self-start justify-self-start whitespace-nowrap">
          {{ t('common.confirm') }}
        </button>
      </form>
    </MagicCard>

    <MagicCard v-if="selectedListing && selectedListingId" class="p-6">
      <h2 class="mb-1 font-semibold">{{ t('publisher.releases') }}</h2>
      <p class="mb-3 text-xs text-muted dark:text-slate-400">{{ selectedListing.name }}</p>
      <p v-if="!store.releasesByListing[selectedListingId]?.length" class="text-sm text-muted">
        {{ t('publisher.noReleases') }}
      </p>
      <ul v-else class="divide-y divide-line dark:divide-slate-800">
        <li
          v-for="release in store.releasesByListing[selectedListingId]"
          :key="release.releaseId"
          class="-mx-2 flex cursor-pointer flex-wrap items-center gap-3 rounded-lg px-2 py-3 text-sm transition-colors hover:bg-surface-muted/70 dark:hover:bg-slate-800/60"
          :class="currentRelease?.releaseId === release.releaseId ? 'text-accent' : ''"
          @click="pickRelease(release)"
        >
          <code class="font-semibold">v{{ release.version }}</code>
          <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
          <StateChip :status="release.status" />
          <span class="ml-auto text-xs text-muted dark:text-slate-400">
            {{ releaseRowHint(release.status) || formatDate(release.createdAt) }}
          </span>
        </li>
      </ul>
    </MagicCard>

    <MagicCard v-if="selectedListing" class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.newRelease') }}</h2>
      <form class="grid gap-3 sm:grid-cols-4" @submit.prevent="createRelease">
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
        <button type="submit" :disabled="busy" class="btn btn-primary self-end justify-self-start whitespace-nowrap">{{ t('publisher.createReleaseAction') }}</button>
      </form>

      <div v-if="currentRelease" class="mt-6 space-y-4 border-t border-line pt-5 dark:border-slate-800">
        <div class="flex items-center gap-2">
          <StateChip :status="currentRelease.status" />
          <Badge tone="muted">{{ currentRelease.version }}</Badge>
        </div>
        <p v-if="releaseMessage" class="alert alert-info" role="status">
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
        <ul v-if="currentRelease.findings?.length" class="space-y-1 text-sm">
          <li v-for="finding in currentRelease.findings" :key="finding.rule" class="card p-2">
            <Badge :tone="finding.severity === 'ERROR' || finding.severity === 'CRITICAL' ? 'danger' : 'muted'">
              {{ finding.severity }}
            </Badge>
            {{ finding.rule }} — {{ finding.message }}
          </li>
        </ul>
      </div>
    </MagicCard>
  </div>
</template>
