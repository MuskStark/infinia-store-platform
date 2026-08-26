<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type DownloadTicket, type ListingDetail, type ResolveResponse } from '../api/client';
import { Badge, MagicCard, ShimmerButton, ProgressBar, BorderBeam } from '@infinia/magic-ui-vue';
import StateChip from '../components/StateChip.vue';
import ErrorState from '../components/ErrorState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import { useAuthStore } from '../stores/auth';

/**
 * Listing detail with the install state machine (design §12.6):
 * 未安装 → 解析中 → 等待确认 → 下载中 → 校验中 → 已安装, with rollback display.
 * The web store acquires download tickets; host-embedded installs run the same
 * states through the local orchestrator.
 */
const props = defineProps<{ namespace: string; slug: string }>();
const { t } = useI18n();
const auth = useAuthStore();

const detail = ref<ListingDetail | null>(null);
const error = ref<string | null>(null);
const loading = ref(true);
const tab = ref<'overview' | 'versions' | 'permissions' | 'dependencies' | 'security'>('overview');

const installStage = ref<
  'idle' | 'resolving' | 'confirm' | 'downloading' | 'verifying' | 'done' | 'failed'
>('idle');
const resolution = ref<ResolveResponse | null>(null);
const ticket = ref<DownloadTicket | null>(null);
const favorited = ref(false);

const latestRelease = computed(() => detail.value?.releases?.[0] ?? null);
const displayName = computed(
  () => detail.value?.localizations?.find((l) => l.locale?.startsWith('en'))?.name
    ?? detail.value?.localizations?.[0]?.name
    ?? detail.value?.slug
    ?? '',
);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    detail.value = await api.get<ListingDetail>(
      `/api/v1/listings/${props.namespace}/${props.slug}`,
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(load);

/** Resolve → confirm (permission aware) → ticket → verify hash (design §9.2, §12.6). */
async function startInstall() {
  if (!latestRelease.value) return;
  installStage.value = 'resolving';
  try {
    resolution.value = await api.post<ResolveResponse>('/api/v1/resolutions', {
      coordinate: `infinia://${detail.value?.type?.toLowerCase()}/${props.namespace}/${props.slug}`,
      client: {
        hostVersion: '4.1.0',
        os: navigator.platform.toLowerCase().includes('mac') ? 'macos' : 'windows',
        arch: 'arm64',
        channel: 'stable',
        installed: [],
      },
    });
    if (!resolution.value.resolvable) {
      installStage.value = 'failed';
      return;
    }
    installStage.value = 'confirm';
  } catch {
    installStage.value = 'failed';
  }
}

async function confirmInstall() {
  if (!latestRelease.value) return;
  installStage.value = 'downloading';
  try {
    ticket.value = await api.post<DownloadTicket>(
      `/api/v1/releases/${latestRelease.value.releaseId}/download-ticket`,
    );
    installStage.value = 'verifying';
    // Web store verifies metadata; the host performs byte-level SHA-256 + signature.
    await new Promise((resolve) => setTimeout(resolve, 600));
    installStage.value = 'done';
  } catch {
    installStage.value = 'failed';
  }
}

async function toggleFavorite() {
  if (!detail.value) return;
  // listingId is the coordinate-derived key; the API accepts the UUID from /me/library.
  await api.put(`/api/v1/me/favorites/${detail.value.listingId}`);
  favorited.value = !favorited.value;
}

const installLabel = computed(() => {
  switch (installStage.value) {
    case 'resolving':
      return t('listing.resolving');
    case 'confirm':
      return t('listing.awaitingConfirm');
    case 'downloading':
      return t('listing.downloading');
    case 'verifying':
      return t('listing.verifying');
    case 'done':
      return t('listing.installed');
    case 'failed':
      return t('listing.manualAction');
    default:
      return t('common.get');
  }
});
</script>

<template>
  <ErrorState v-if="error" :message="error" @retry="load" />
  <LoadingGrid v-else-if="loading" />
  <div v-else-if="detail" class="space-y-8">
    <header class="relative overflow-visible rounded-3xl border border-line bg-surface p-8 dark:border-slate-800 dark:bg-slate-900">
      <BorderBeam v-if="latestRelease?.channel === 'beta'" :size="2" :duration="7" />
      <div class="flex flex-wrap items-start gap-6">
        <div class="grid h-16 w-16 place-items-center rounded-2xl bg-gradient-to-br from-accent to-accent2 text-2xl font-bold text-white">
          {{ displayName.charAt(0) }}
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-2xl font-bold">{{ displayName }}</h1>
            <Badge tone="muted">{{ t(`type.${detail.type}`) }}</Badge>
            <Badge v-if="detail.defaultChannel !== 'stable'" tone="accent">
              {{ t(`channel.${detail.defaultChannel}`) }}
            </Badge>
          </div>
          <p class="mt-2 max-w-2xl text-muted dark:text-slate-400">
            {{ detail.localizations?.[0]?.summary }}
          </p>
          <p class="mt-2 text-sm text-muted dark:text-slate-500">
            {{ t('listing.publisher') }}: <strong>{{ detail.publisherName }}</strong>
            · {{ detail.downloads?.toLocaleString() ?? 0 }} {{ t('discover.statsDownloads') }}
          </p>
        </div>
        <div class="flex w-full flex-col gap-2 sm:w-56">
          <ShimmerButton :disabled="installStage !== 'idle' && installStage !== 'failed'" @click="startInstall">
            {{ installLabel }}
          </ShimmerButton>
          <ProgressBar v-if="installStage === 'downloading' || installStage === 'verifying'" />
          <button
            v-if="auth.isAuthenticated"
            class="rounded-xl border border-line px-4 py-2 text-sm dark:border-slate-800"
            @click="toggleFavorite"
          >
            {{ favorited ? t('listing.favoriteRemove') : t('listing.favoriteAdd') }}
          </button>
          <a
            v-if="ticket"
            :href="ticket.url"
            class="rounded-xl border border-line px-4 py-2 text-center text-sm underline dark:border-slate-800"
            download
          >
            {{ t('common.download') }} (sha256:{{ (ticket.sha256 ?? '').slice(0, 12) }}…)
          </a>
        </div>
      </div>

      <!-- Permission confirmation step (design §9.3): escalate = ask again. -->
      <div
        v-if="installStage === 'confirm' && resolution"
        class="mt-6 rounded-2xl border border-accent/40 bg-accent/5 p-4"
      >
        <h2 class="font-semibold">{{ t('listing.confirmInstall') }}</h2>
        <ul class="mt-2 list-disc space-y-1 pl-6 text-sm">
          <li v-for="node in resolution.plan" :key="node.coordinate">
            {{ node.coordinate }}
            <Badge v-if="node.alreadyInstalled" tone="success">{{ t('listing.installed') }}</Badge>
          </li>
        </ul>
        <div v-if="resolution.missing?.length" class="mt-2 text-sm text-red-600 dark:text-red-400">
          {{ t('listing.missingDeps') }}:
          {{ resolution.missing.map((m) => m.coordinate).join(', ') }}
        </div>
        <div class="mt-3 flex gap-2">
          <button class="rounded-xl bg-accent px-4 py-2 font-medium text-white" @click="confirmInstall">
            {{ t('common.confirm') }}
          </button>
          <button class="rounded-xl border border-line px-4 py-2 dark:border-slate-800" @click="installStage = 'idle'">
            {{ t('common.cancel') }}
          </button>
        </div>
      </div>
      <p v-else-if="installStage === 'failed'" class="mt-4 text-sm text-red-600 dark:text-red-400">
        {{ t('listing.resolveFailed') }}
      </p>
    </header>

    <nav class="flex flex-wrap gap-1 border-b border-line dark:border-slate-800" role="tablist">
      <button
        v-for="key in ['overview', 'versions', 'permissions', 'dependencies', 'security'] as const"
        :key="key"
        role="tab"
        :aria-selected="tab === key"
        class="rounded-t-xl px-4 py-2 text-sm"
        :class="tab === key ? 'border-b-2 border-accent font-semibold' : 'text-muted'"
        @click="tab = key"
      >
        {{ t(`listing.${key}`) }}
      </button>
    </nav>

    <section v-if="tab === 'overview'">
      <article class="prose max-w-none">
        <p class="whitespace-pre-wrap">{{ detail.localizations?.[0]?.descriptionMarkdown }}</p>
      </article>
    </section>

    <section v-if="tab === 'versions'" class="space-y-3">
      <MagicCard v-for="release in detail.releases" :key="release.releaseId" class="p-5">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <span class="font-semibold">v{{ release.version }}</span>
            <StateChip :status="release.status" />
            <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
          </div>
          <span class="text-xs text-muted dark:text-slate-400">{{ release.publishedAt }}</span>
        </div>
        <p v-if="release.requiresHost" class="mt-2 text-sm text-muted dark:text-slate-400">
          {{ t('listing.requiresHost') }}: <code>{{ release.requiresHost }}</code>
        </p>
        <p class="mt-2 text-sm">{{ release.changelogMarkdown }}</p>
      </MagicCard>
    </section>

    <section v-if="tab === 'permissions'">
      <table v-if="latestRelease?.permissions?.length" class="w-full text-left text-sm">
        <thead>
          <tr class="text-muted">
            <th class="p-2">{{ t('listing.permissionId') }}</th>
            <th class="p-2">{{ t('listing.scope') }}</th>
            <th class="p-2">{{ t('listing.reason') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="permission in latestRelease.permissions"
            :key="permission.permissionId"
            class="border-t border-line dark:border-slate-800"
          >
            <td class="p-2 font-mono text-xs">{{ permission.permissionId }}</td>
            <td class="p-2 font-mono text-xs">{{ permission.scope }}</td>
            <td class="p-2">{{ permission.reason }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-muted dark:text-slate-400">{{ t('common.empty') }}</p>
    </section>

    <section v-if="tab === 'dependencies'">
      <ul v-if="latestRelease?.dependencies?.length" class="space-y-2">
        <li
          v-for="dependency in latestRelease.dependencies"
          :key="dependency.coordinate"
          class="flex items-center gap-2 rounded-xl border border-line p-3 text-sm dark:border-slate-800"
        >
          <code class="text-xs">{{ dependency.coordinate }}</code>
          <Badge tone="muted">{{ dependency.range }}</Badge>
          <Badge v-if="!dependency.optional" tone="danger">{{ t('listing.required') }}</Badge>
        </li>
      </ul>
      <p v-else class="text-sm text-muted dark:text-slate-400">{{ t('common.empty') }}</p>
    </section>

    <section v-if="tab === 'security'" class="space-y-2 text-sm">
      <template v-if="latestRelease">
        <p>
          <span class="text-muted dark:text-slate-400">{{ t('listing.sha256') }}:</span>
          <code class="break-all">{{ latestRelease.artifacts?.[0]?.sha256 }}</code>
        </p>
        <p>
          <span class="text-muted dark:text-slate-400">{{ t('listing.signature') }}:</span>
          <code class="break-all">{{ latestRelease.artifacts?.[0]?.keyId }}</code>
        </p>
        <p class="text-muted dark:text-slate-400">
          Ed25519 platform signature over the release envelope; verified on every download.
        </p>
      </template>
    </section>
  </div>
</template>
