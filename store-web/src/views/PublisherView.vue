<script setup lang="ts">
import { onMounted, ref } from 'vue';
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
const selectedListing = ref<CatalogItem | null>(null);
const selectedListingId = ref<string | null>(null);
const currentRelease = ref<PublisherRelease | null>(null);
const busy = ref(false);

async function createOrg() {
  busy.value = true;
  try {
    const created = await api.post<{ slug: string }>('/api/v1/organizations', orgForm.value);
    message.value = t('publisher.orgCreated');
    await loadOrgNamespaces();
    listingForm.value.namespace = created?.slug ?? orgForm.value.slug;
    useCustomNamespace.value = false;
    orgForm.value = { slug: '', name: '' };
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
  } finally {
    busy.value = false;
  }
}

/** Publisher-side Infinia Level gate adjustment (Infinia Level 门槛). */
const gateListingId = ref('');
const gateLevel = ref(0);

async function applyGate() {
  if (!gateListingId.value) return;
  busy.value = true;
  try {
    await api.post(
      `/api/v1/publisher/listings/${gateListingId.value}/min-bee-level`,
      { minBeeLevel: gateLevel.value },
    );
    message.value = t('publisher.gateUpdated');
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
    await store.loadReleases(detail.listingId);
    message.value = t('publisher.releaseCreated');
  } catch (e) {
    message.value = problemText(e);
  } finally {
    busy.value = false;
  }
}

function onPackageChange() {
  packageName.value = fileInput.value?.files?.[0]?.name ?? '';
}

async function uploadAndSubmit() {
  const file = fileInput.value?.files?.[0];
  const release = currentRelease.value;
  if (!file || !release) return;
  busy.value = true;
  try {
    const session = await api.post<UploadSession>(
      `/api/v1/publisher/releases/${release.releaseId}/uploads`,
      { filename: file.name },
    );
    await api.putRaw(session.uploadUrl, await file.arrayBuffer());
    message.value = t('publisher.uploadDone');
    const result = await api.post<SubmitResult>(
      `/api/v1/publisher/releases/${release.releaseId}/submit`,
    );
    await pollStatus();
    message.value = `${t('publisher.submit')}: ${result.status}`;
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
  packageName.value = '';
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
      message.value = t('publisher.draftResumed');
    }
  } catch {
    /* detail load failure leaves the wizard usable for new releases */
    message.value = t('publisher.releasesUnavailable');
  }
}

async function pickRelease(release: PublisherRelease) {
  currentRelease.value = release;
  message.value = '';
  packageName.value = '';
}

async function refreshSelectedListingReleases() {
  const listingId = selectedListingId.value;
  if (!listingId) return;
  await store.loadReleases(listingId);
  const fresh = store.releasesByListing[listingId]?.find(
    (r) => r.releaseId === currentRelease.value?.releaseId,
  );
  if (fresh) currentRelease.value = fresh;
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
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">{{ t('common.confirm') }}</button>
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
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">{{ t('common.confirm') }}</button>
      </form>
    </MagicCard>

    <MagicCard class="p-6">
      <h2 class="mb-2 font-semibold">{{ t('publisher.setGate') }}</h2>
      <p class="mb-3 text-sm text-muted">{{ t('publisher.setGateHint') }}</p>
      <form class="grid gap-3 sm:grid-cols-3" @submit.prevent="applyGate">
        <input
          v-model="gateListingId"
          required
          placeholder="listing UUID"
          class="input font-mono"
        />
        <SelectMenu
          :model-value="gateLevel"
          :options="BEE_LEVEL_OPTIONS"
          :aria-label="t('publisher.minBeeLevel')"
          @update:model-value="gateLevel = Number($event)"
        />
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">
          {{ t('common.confirm') }}
        </button>
      </form>
    </MagicCard>

    <MagicCard v-if="selectedListing && selectedListingId" class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.releases') }}</h2>
      <p v-if="!store.releasesByListing[selectedListingId]?.length" class="text-sm text-muted">
        {{ t('publisher.noReleases') }}
      </p>
      <ul v-else class="divide-y divide-line dark:divide-slate-800">
        <li
          v-for="release in store.releasesByListing[selectedListingId]"
          :key="release.releaseId"
          class="flex cursor-pointer flex-wrap items-center gap-3 py-3 text-sm"
          :class="currentRelease?.releaseId === release.releaseId ? 'text-accent' : ''"
          @click="release.status === 'DRAFT' && pickRelease(release)"
        >
          <code class="font-semibold">v{{ release.version }}</code>
          <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
          <StateChip :status="release.status" />
          <span class="ml-auto text-xs text-muted dark:text-slate-400">
            {{ release.status === 'DRAFT' ? t('publisher.clickToResume') : formatDate(release.createdAt) }}
          </span>
        </li>
      </ul>
    </MagicCard>

    <MagicCard v-if="selectedListing" class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.newRelease') }}</h2>
      <form class="grid gap-3 sm:grid-cols-4" @submit.prevent="createRelease">
        <input v-model="releaseForm.version" required placeholder="1.0.0" class="input" />
        <SelectMenu v-model="releaseForm.channel" :options="CHANNEL_OPTIONS" :aria-label="t('listing.channel')" />
        <input v-model="releaseForm.requiresHost" placeholder=">=4.0.0 <5.0.0" class="input" />
        <button type="submit" :disabled="busy" class="btn btn-primary self-start justify-self-start whitespace-nowrap">{{ t('common.confirm') }}</button>
      </form>

      <div v-if="currentRelease" class="mt-6 space-y-4">
        <div class="flex items-center gap-2">
          <StateChip :status="currentRelease.status" />
          <Badge tone="muted">{{ currentRelease.version }}</Badge>
        </div>
        <ProgressBar v-if="currentRelease.status === 'SCANNING'" />
        <div v-if="currentRelease.status === 'DRAFT'" class="space-y-2">
          <!-- Same hidden-input + styled picker pattern as the admin app-release
               upload, so both package uploaders render identically. -->
          <input ref="fileInput" type="file" class="hidden" @change="onPackageChange" />
          <div class="flex flex-wrap items-center gap-3">
            <button type="button" class="btn btn-secondary" @click="fileInput?.click()">
              {{ t('publisher.uploadPackage') }}
            </button>
            <span class="min-w-0 flex-1 truncate text-sm" :class="packageName ? '' : 'text-muted dark:text-slate-400'">
              {{ packageName || '—' }}
            </span>
          </div>
          <button :disabled="busy || !packageName" class="btn btn-primary" @click="uploadAndSubmit">
            {{ t('publisher.submit') }}
          </button>
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
