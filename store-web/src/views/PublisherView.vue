<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  api,
  type CatalogItem,
  type ListingDetail,
  type PublisherRelease,
  type SubmitResult,
  type UploadSession,
} from '../api/client';
import { Badge, MagicCard, ProgressBar, ShimmerButton } from '@infinia/magic-ui-vue';
import StateChip from '../components/StateChip.vue';
import EmptyState from '../components/EmptyState.vue';
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
});
const releaseForm = ref({ version: '', channel: 'stable', requiresHost: '' });
const fileInput = ref<HTMLInputElement | null>(null);
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

async function createRelease() {
  const selected = selectedListing.value;
  if (!selected?.coordinate) return;
  busy.value = true;
  try {
    // Resolve the listing UUID from the public detail endpoint.
    const [, type, namespace, slug] = selected.coordinate.split('/');
    const detail = await api.get<ListingDetail>(
      `/api/v1/listings/${namespace}/${slug}`,
    );
    void type;
    const release = await api.post<PublisherRelease>(
      `/api/v1/publisher/listings/${detail.listingId}/releases`,
      releaseForm.value,
    );
    selectedListingId.value = detail.listingId;
    currentRelease.value = release;
    await store.loadReleases(detail.listingId);
    message.value = t('publisher.releaseCreated');
  } finally {
    busy.value = false;
  }
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
  // Resolve the listing UUID, then load its releases (incl. DRAFTs) so an
  // interrupted draft can be resumed — the upload area keys off currentRelease.
  try {
    const [, , namespace, slug] = listing.coordinate.split('/');
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
  }
}

async function pickRelease(release: PublisherRelease) {
  currentRelease.value = release;
  message.value = '';
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
</script>

<template>
  <div class="space-y-8">
    <h1 class="text-2xl font-bold">{{ t('publisher.title') }}</h1>
    <p class="text-sm text-muted dark:text-slate-400">{{ t('publisher.steps') }}</p>
    <p v-if="message" class="rounded-xl bg-accent/10 p-3 text-sm text-accent" role="status">
      {{ message }}
    </p>

    <section>
      <h2 class="mb-3 text-lg font-semibold">{{ t('publisher.listings') }}</h2>
      <EmptyState v-if="!store.listings.length" :title="t('common.empty')" />
      <ul v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <li
          v-for="listing in store.listings"
          :key="listing.coordinate"
          class="cursor-pointer rounded-2xl border p-4 text-sm"
          :class="selectedListing?.coordinate === listing.coordinate
            ? 'border-accent'
            : 'border-line dark:border-slate-800'"
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
        <input v-model="orgForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" :placeholder="t('publisher.orgSlug')" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <input v-model="orgForm.name" :placeholder="t('publisher.orgName')" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <ShimmerButton type="submit" :disabled="busy" class="shrink-0 whitespace-nowrap">{{ t('common.confirm') }}</ShimmerButton>
      </form>
    </MagicCard>

    <MagicCard class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.newListings') }}</h2>
      <form class="grid gap-3 sm:grid-cols-3" @submit.prevent="createListing">
        <select
          v-if="!useCustomNamespace"
          :value="listingForm.namespace"
          required
          class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
          :aria-label="t('publisher.namespace')"
          @change="onNamespaceChange(($event.target as HTMLSelectElement).value)"
        >
          <option v-if="!orgNamespaces.length" value="" disabled>
            {{ t('publisher.namespaceNone') }}
          </option>
          <option v-for="ns in orgNamespaces" :key="ns" :value="ns">{{ ns }}</option>
          <option :value="CUSTOM_NAMESPACE">{{ t('publisher.namespaceCustom') }}</option>
        </select>
        <input
          v-else
          v-model="listingForm.namespace"
          required
          :placeholder="t('publisher.namespace')"
          class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
        />
        <button
          v-if="useCustomNamespace"
          type="button"
          class="shrink-0 whitespace-nowrap rounded-xl border border-line px-3 py-2 text-sm text-muted dark:border-slate-800 dark:text-slate-400"
          @click="useCustomNamespace = false"
        >
          {{ t('publisher.namespaceBackToList') }}
        </button>
        <input v-model="listingForm.slug" required pattern="[a-z0-9][a-z0-9-]{0,62}" :placeholder="t('publisher.slug')" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <select v-model="listingForm.type" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900">
          <option v-for="type in ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW']" :key="type" :value="type">
            {{ t(`type.${type}`) }}
          </option>
        </select>
        <input v-model="listingForm.name" required :placeholder="t('publisher.name')" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <input v-model="listingForm.summary" :placeholder="t('publisher.summary')" class="sm:col-span-2 rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <ShimmerButton type="submit" :disabled="busy" class="shrink-0 whitespace-nowrap">{{ t('common.confirm') }}</ShimmerButton>
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
          <Badge tone="muted">{{ release.channel }}</Badge>
          <StateChip :status="release.status" />
          <span class="ml-auto text-xs text-muted dark:text-slate-400">
            {{ release.status === 'DRAFT' ? t('publisher.clickToResume') : release.createdAt?.slice(0, 10) }}
          </span>
        </li>
      </ul>
    </MagicCard>

    <MagicCard v-if="selectedListing" class="p-6">
      <h2 class="mb-4 font-semibold">{{ t('publisher.newRelease') }}</h2>
      <form class="grid gap-3 sm:grid-cols-4" @submit.prevent="createRelease">
        <input v-model="releaseForm.version" required placeholder="1.0.0" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <select v-model="releaseForm.channel" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900">
          <option value="stable">{{ t('channel.stable') }}</option>
          <option value="beta">{{ t('channel.beta') }}</option>
        </select>
        <input v-model="releaseForm.requiresHost" placeholder=">=4.0.0 <5.0.0" class="rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900" />
        <ShimmerButton type="submit" :disabled="busy" class="shrink-0 whitespace-nowrap">{{ t('common.confirm') }}</ShimmerButton>
      </form>

      <div v-if="currentRelease" class="mt-6 space-y-4">
        <div class="flex items-center gap-2">
          <StateChip :status="currentRelease.status" />
          <Badge tone="muted">{{ currentRelease.version }}</Badge>
        </div>
        <ProgressBar v-if="currentRelease.status === 'SCANNING'" />
        <div v-if="currentRelease.status === 'DRAFT'" class="space-y-2">
          <label class="text-sm">
            {{ t('publisher.uploadPackage') }}
            <input ref="fileInput" type="file" class="mt-1 block w-full text-sm" />
          </label>
          <ShimmerButton :disabled="busy" @click="uploadAndSubmit">
            {{ t('publisher.submit') }}
          </ShimmerButton>
        </div>
        <ul v-if="currentRelease.findings?.length" class="space-y-1 text-sm">
          <li v-for="finding in currentRelease.findings" :key="finding.rule" class="rounded-lg border border-line p-2 dark:border-slate-800">
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
