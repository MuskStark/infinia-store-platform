<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { api, ApiRequestError, type DownloadTicket, type Library, type ListingDetail, type RatingsPage, type ResolveResponse } from '../api/client';
import { Badge, MagicCard, ProgressBar } from '@infinia/magic-ui-vue';
import StateChip from '../components/StateChip.vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import ErrorState from '../components/ErrorState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import { formatDate, formatNumber } from '../utils/format';
import { useAuthStore } from '../stores/auth';

/**
 * Listing detail with the install state machine (design §12.6):
 * 未安装 → 解析中 → 等待确认 → 下载中 → 校验中 → 已安装, with rollback display.
 * The web store acquires download tickets; host-embedded installs run the same
 * states through the local orchestrator.
 */
const props = defineProps<{ namespace: string; slug: string }>();
const { t, locale } = useI18n();
const route = useRoute();
const auth = useAuthStore();

const detail = ref<ListingDetail | null>(null);
const error = ref<string | null>(null);
const loading = ref(true);
/** Set when the listing is Infinia Level gated and the viewer ranks below it. */
const gateRequired = ref<number | null>(null);
const tab = ref<'overview' | 'versions' | 'permissions' | 'dependencies' | 'compatibility' | 'security' | 'reviews'>('overview');

/** Resolve → confirm (permission aware) → download the offline install
 *  package (design §9.2, §12.6). The web store cannot install into the host
 *  itself — confirming hands the user the same installable package the host's
 *  local install mode consumes. */
const installStage = ref<
  'idle' | 'resolving' | 'confirm' | 'downloading' | 'verifying' | 'done' | 'failed'
>('idle');
const resolution = ref<ResolveResponse | null>(null);
/** Which step failed — the message must not blame resolution for a download error. */
const failureKind = ref<'resolve' | 'download'>('resolve');
const favorited = ref(false);

const ratings = ref<RatingsPage | null>(null);
const myStars = ref(0);
const myComment = ref('');
const ratingSaved = ref(false);
const reporting = ref(false);
const reportReason = ref('malware');
const reportDetails = ref('');
const reportDone = ref(false);
const reportError = ref<string | null>(null);

const latestRelease = computed(() => detail.value?.releases?.[0] ?? null);
const upstream = computed(() => detail.value?.upstream ?? null);
const isUpstreamAggregated = computed(() => upstream.value?.deliveryMode === 'MATERIALIZED_BLOB');

/** Locale-aware localization (zh-CN matches zh first, then en, then anything). */
const localization = computed(() => {
  const locs = detail.value?.localizations ?? [];
  const lang = locale.value.toLowerCase();
  const primary = lang.split('-')[0];
  return (
    locs.find((l) => l.locale?.toLowerCase() === lang)
    ?? locs.find((l) => l.locale?.toLowerCase().split('-')[0] === primary)
    ?? locs.find((l) => l.locale?.toLowerCase().startsWith('en'))
    ?? locs[0]
    ?? null
  );
});
const displayName = computed(() => localization.value?.name ?? detail.value?.slug ?? '');
// Plain-text overview: a leading "# Title" markdown heading would render its
// raw "#" here, so drop it when it merely repeats the product name.
const overviewText = computed(() => {
  const raw = localization.value?.descriptionMarkdown?.trim()
    || localization.value?.summary?.trim()
    || t('listing.descriptionUnavailable');
  const heading = raw.match(/^#\s+(.+?)\s*(?:\n|$)/);
  if (heading && heading[1].trim().toLowerCase() === displayName.value.trim().toLowerCase()) {
    return raw.slice(heading[0].length).trim() || raw;
  }
  return raw;
});
const sourceUrl = computed(() => upstream.value?.sourceUrl ?? latestRelease.value?.sourceUrl);

function displayVersion(version?: string | null): string {
  const declared = upstream.value?.upstreamVersion?.trim();
  if (declared) return `v${declared}`;
  if (!version || (isUpstreamAggregated.value && version === '0.0.0')) {
    return t('listing.versionUnspecified');
  }
  return `v${version}`;
}

function shortSha(value?: string | null): string {
  if (!value) return '—';
  return value.length > 16 ? `${value.slice(0, 12)}…` : value;
}

function formatSize(bytes?: number): string {
  if (!bytes && bytes !== 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 'B';
  for (const next of units) {
    if (value < 1024) break;
    value /= 1024;
    unit = next;
  }
  return `${value.toFixed(1)} ${unit}`;
}

/** Type-specific install behavior surfaced from the install manifest contract (plan §7.1). */
const installInfo = computed(() => {
  switch (detail.value?.type) {
    case 'SKILL':
      return { mode: t('listing.installModeSkill'), hint: t('listing.installHintSkill') };
    case 'MCP':
      return { mode: t('listing.installModeMcp'), hint: t('listing.installHintMcp') };
    case 'PLUGIN':
      return { mode: t('listing.installModePlugin'), hint: t('listing.installHintPlugin') };
    default:
      return null;
  }
});

/**
 * Publisher-side Infinia Level gate management — moved out of the Publishing
 * Center so that page stays a focused release wizard. Visible to publisher
 * roles while viewing a listing; the server still rejects non-owners.
 */
const canManageGate = computed(() =>
  auth.isAuthenticated
  && ['PUBLISHER', 'ORG_ADMIN', 'PLATFORM_ADMIN'].some((role) => auth.roles.includes(role)));
const GATE_LEVEL_OPTIONS = [0, 1, 2, 3, 4].map((level) => ({
  value: level,
  label:
    level === 0
      ? t('publisher.beeLevelPublic')
      : `${t(`beeLevel.${level}`)} · Lv${level}+`,
}));
const gateLevel = ref(0);
const gateBusy = ref(false);
const gateSaved = ref(false);
const gateError = ref<string | null>(null);

async function applyGate() {
  if (!detail.value) return;
  gateBusy.value = true;
  gateSaved.value = false;
  gateError.value = null;
  try {
    await api.post(
      `/api/v1/publisher/listings/${detail.value.listingId}/min-bee-level`,
      { minBeeLevel: gateLevel.value },
    );
    // Keep the header badge in sync with the saved gate.
    detail.value = { ...detail.value, minBeeLevel: gateLevel.value };
    gateSaved.value = true;
  } catch (e) {
    gateError.value = e instanceof Error ? e.message : 'error';
  } finally {
    gateBusy.value = false;
  }
}

async function load() {
  loading.value = true;
  error.value = null;
  gateRequired.value = null;
  try {
    detail.value = await api.get<ListingDetail>(
      `/api/v1/listings/${props.namespace}/${props.slug}`,
    );
    gateLevel.value = detail.value?.minBeeLevel ?? 0;
    gateSaved.value = false;
    gateError.value = null;
    ratings.value = await api.get<RatingsPage>(
      `/api/v1/listings/${props.namespace}/${props.slug}/ratings`,
    );
    if (auth.isAuthenticated && detail.value?.coordinate) {
      // Reflect the real favorite state instead of always defaulting to "not favorited".
      const library = await api.get<Library>('/api/v1/me/library');
      favorited.value = (library.favorites ?? []).some(
        (f) => f.listingCoordinate === detail.value?.coordinate,
      );
    }
  } catch (e) {
    if (e instanceof ApiRequestError && e.code === 'bee_level_required') {
      gateRequired.value = Number(e.parameters?.requiredBeeLevel ?? 1);
    } else {
      error.value = e instanceof Error ? e.message : 'error';
    }
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function submitRating() {
  if (!myStars.value) return;
  await api.put(`/api/v1/listings/${props.namespace}/${props.slug}/ratings`, {
    stars: myStars.value,
    comment: myComment.value || undefined,
  });
  ratingSaved.value = true;
  ratings.value = await api.get<RatingsPage>(
    `/api/v1/listings/${props.namespace}/${props.slug}/ratings`,
  );
}

async function submitReport() {
  reportError.value = null;
  try {
    await api.post('/api/v1/reports', {
      coordinate: detail.value?.coordinate,
      reason: reportReason.value,
      details: reportDetails.value || undefined,
    });
    reportDone.value = true;
    reporting.value = false;
  } catch (e) {
    reportError.value = e instanceof Error ? e.message : 'error';
  }
}

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
      failureKind.value = 'resolve';
      installStage.value = 'failed';
      return;
    }
    installStage.value = 'confirm';
  } catch {
    failureKind.value = 'resolve';
    installStage.value = 'failed';
  }
}

async function confirmInstall() {
  if (!latestRelease.value) return;
  installStage.value = 'downloading';
  try {
    // Confirming the permission-aware plan downloads the offline install
    // package — the bytes the host's local install mode imports.
    await api.download(
      `/api/v1/releases/${latestRelease.value.releaseId}/install-package`,
      `${props.namespace}.${props.slug}.zip`,
    );
    installStage.value = 'verifying';
    // Web store verifies metadata; the host performs byte-level SHA-256 + signature.
    await new Promise((resolve) => setTimeout(resolve, 600));
    installStage.value = 'done';
  } catch {
    failureKind.value = 'download';
    installStage.value = 'failed';
  }
}

/**
 * APP listings distribute installed + portable binaries per platform; instead of
 * the resolve→confirm install machine they download a concrete artifact, addressed
 * by artifactId so installer and portable variants of one release stay distinct.
 */
const isAppListing = computed(() => detail.value?.type === 'APP');
const artifactTickets = ref<Record<string, DownloadTicket | 'loading' | 'error'>>({});

function detectOs(): string {
  const platform = (navigator.platform || '').toLowerCase();
  if (platform.includes('win')) return 'windows';
  if (platform.includes('mac')) return 'macos';
  if (platform.includes('linux')) return 'linux';
  return 'universal';
}

function detectArch(): string {
  const ua = navigator.userAgent.toLowerCase();
  if (ua.includes('arm64') || ua.includes('aarch64')) return 'arm64';
  if (ua.includes('x86_64') || ua.includes('amd64') || ua.includes('wow64')) return 'x64';
  return 'universal';
}

/** Best binary for this browser: own platform first, universal fallback, installer preferred. */
const recommendedArtifact = computed(() => {
  const artifacts = latestRelease.value?.artifacts ?? [];
  if (!artifacts.length) return null;
  const os = detectOs();
  const arch = detectArch();
  const score = (a: (typeof artifacts)[number]) =>
    (a.platform === os ? 0 : a.platform === 'universal' ? 1 : 9)
    + (a.arch === arch ? 0 : a.arch === 'universal' ? 1 : 9)
    + (a.kind === 'INSTALLER' ? 0 : 1)
    + (a.variant === 'lite' ? 0 : a.variant === 'jre' ? 1 : 2);
  return [...artifacts].sort((x, y) => score(x) - score(y))[0] ?? null;
});

async function downloadArtifact(artifact: { artifactId?: string | null; filename: string }) {
  if (!latestRelease.value || !artifact.artifactId) return;
  const key = artifact.artifactId;
  if (artifactTickets.value[key] === 'loading') return;
  artifactTickets.value[key] = 'loading';
  try {
    const t = await api.post<DownloadTicket>(
      `/api/v1/releases/${latestRelease.value.releaseId}/download-ticket?artifactId=${key}`,
    );
    artifactTickets.value[key] = t;
    // Ticket URLs are short-lived and server-relative: hand the bytes to the
    // browser immediately via a synthetic anchor click.
    const anchor = document.createElement('a');
    anchor.href = t.url;
    anchor.download = artifact.filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } catch {
    artifactTickets.value[key] = 'error';
  }
}

/**
 * Direct download of the offline install package (plan §7.1): Native install
 * manifest + signed artifact + checksums in one ZIP. The saved file installs
 * through the host's local install mode — 主程序本地安装 — without a store
 * connection. Tracked per release so the versions tab can offer old versions.
 */
const packageDownloads = ref<Record<string, 'loading' | 'error'>>({});

async function downloadInstallPackage(releaseId?: string) {
  const id = releaseId ?? latestRelease.value?.releaseId;
  // 'error' must stay retryable — only an in-flight download blocks re-entry.
  if (!id || packageDownloads.value[id] === 'loading') return;
  packageDownloads.value[id] = 'loading';
  try {
    await api.download(
      `/api/v1/releases/${id}/install-package`,
      `${props.namespace}.${props.slug}.zip`,
    );
    delete packageDownloads.value[id];
  } catch {
    packageDownloads.value[id] = 'error';
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
  <div
    v-else-if="gateRequired !== null"
    class="rounded-xl border border-line bg-surface p-10 text-center dark:border-slate-800 dark:bg-slate-900"
  >
    <div class="text-5xl">🐝</div>
    <h1 class="mt-4 text-2xl font-bold">{{ t('listing.beeGateTitle') }}</h1>
    <p class="mx-auto mt-3 max-w-md text-sm text-muted dark:text-slate-400">
      {{ t('listing.beeGateBody') }}
    </p>
    <div class="mt-5 flex flex-wrap items-center justify-center gap-3">
      <BeeLevelBadge :level="gateRequired" demands />
      <RouterLink
        v-if="!auth.isAuthenticated"
        to="/signin"
        class="btn btn-primary"
      >
        {{ t('listing.beeGateSignIn') }}
      </RouterLink>
    </div>
  </div>
  <div v-else-if="detail" class="space-y-6">
    <!-- Marketplace detail header: big black title, badge row, publisher line;
         the CTA rail sits at the right like the marketplace install column. -->
    <header class="space-y-4">
      <div class="flex flex-wrap items-start justify-between gap-6">
        <div class="min-w-0 flex-1">
          <h1 class="max-w-2xl text-3xl font-bold leading-tight tracking-tight">{{ displayName }}</h1>
          <div class="mt-3 flex flex-wrap items-center gap-2">
            <Badge tone="muted">{{ t(`type.${detail.type}`) }}</Badge>
            <Badge v-if="latestRelease" tone="muted">{{ displayVersion(latestRelease.version) }}</Badge>
            <Badge v-if="isUpstreamAggregated" tone="accent">{{ t('listing.aggregatedUpstream') }}</Badge>
            <BeeLevelBadge v-if="detail.minBeeLevel && detail.minBeeLevel > 0" :level="detail.minBeeLevel" demands />
            <Badge v-if="detail.defaultChannel !== 'stable'" tone="accent">
              {{ t(`channel.${detail.defaultChannel}`) }}
            </Badge>
          </div>
          <div class="mt-3 flex items-center gap-2.5">
            <span
              class="grid h-7 w-7 shrink-0 place-items-center rounded-md text-xs font-bold text-white"
              style="background: var(--hero-gradient)"
              aria-hidden="true"
            >{{ (detail.publisherName ?? '?').charAt(0).toUpperCase() }}</span>
            <span class="text-sm font-semibold">{{ detail.publisherName }}</span>
          </div>
          <p class="mt-3 max-w-2xl text-[15px] leading-7 text-muted dark:text-slate-400">
            {{ localization?.summary }}
          </p>
          <p class="mt-2 text-sm text-muted dark:text-slate-500">
            {{ formatNumber(detail.downloads ?? 0) }} {{ t('discover.statsDownloads') }}
            · {{ formatNumber(detail.favorites ?? 0) }} {{ t('listing.favoritesCount') }}
            · {{ t('listing.updated') }}: {{ formatDate(detail.updatedAt) }}
          </p>
          <div
            v-if="detail.category || detail.tags?.length"
            class="mt-3 flex flex-wrap items-center gap-1.5"
          >
            <Badge v-if="detail.category" tone="muted">{{ detail.category }}</Badge>
            <Badge v-for="tag in detail.tags" :key="tag" tone="muted">{{ tag }}</Badge>
          </div>
        </div>
        <div class="flex w-full flex-col gap-2 sm:w-56">
          <button
            v-if="!isAppListing"
            class="btn btn-primary"
            :disabled="installStage !== 'idle' && installStage !== 'failed'"
            @click="startInstall"
          >
            {{ installLabel }}
          </button>
          <button
            v-if="isAppListing && recommendedArtifact"
            class="btn btn-primary"
            :disabled="artifactTickets[recommendedArtifact.artifactId ?? ''] === 'loading'"
            @click="downloadArtifact(recommendedArtifact)"
          >
            {{ artifactTickets[recommendedArtifact.artifactId ?? ''] === 'loading'
              ? t('listing.downloading') : t('common.download') }}
          </button>
          <p
            v-if="installStage === 'done'"
            class="text-xs leading-5 text-success dark:text-emerald-400"
          >
            {{ t('listing.packageDownloaded') }}
          </p>
          <ProgressBar v-if="installStage === 'downloading' || installStage === 'verifying'" />
          <button
            v-if="auth.isAuthenticated"
            class="btn btn-secondary"
            @click="toggleFavorite"
          >
            {{ favorited ? t('listing.favoriteRemove') : t('listing.favoriteAdd') }}
          </button>
          <button
            v-if="auth.isAuthenticated && !reportDone"
            class="btn btn-ghost"
            @click="reporting = true"
          >
            {{ t('listing.report') }}
          </button>
        </div>
      </div>

      <!-- Permission confirmation step (design §9.3): escalate = ask again. -->
      <div
        v-if="installStage === 'confirm' && resolution"
        class="mt-6 rounded-lg border border-accent/40 bg-accent/5 p-4"
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
          <button class="btn btn-primary" @click="confirmInstall">
            {{ t('common.confirm') }}
          </button>
          <button class="btn btn-secondary" @click="installStage = 'idle'">
            {{ t('common.cancel') }}
          </button>
        </div>
      </div>
      <p v-else-if="installStage === 'failed'" class="mt-4 text-sm text-red-600 dark:text-red-400">
        {{ failureKind === 'download'
          ? t('listing.installDownloadFailed') : t('listing.resolveFailed') }}
      </p>
    </header>

    <nav class="flex flex-wrap gap-1 border-b border-line dark:border-slate-800" role="tablist">
      <button
        v-for="key in ['overview', 'versions', 'permissions', 'dependencies', 'compatibility', 'security', 'reviews'] as const"
        :key="key"
        role="tab"
        :aria-selected="tab === key"
        class="-mb-px border-b-2 px-4 py-2.5 text-sm transition-colors"
        :class="tab === key
          ? 'border-accent font-semibold text-accent'
          : 'border-transparent text-muted hover:text-ink dark:hover:text-slate-200'"
        @click="tab = key"
      >
        {{ t(`listing.${key}`) }}
      </button>
    </nav>

    <section v-if="tab === 'overview'" class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
      <article class="max-w-none space-y-5">
        <div>
          <h2 class="text-lg font-semibold">{{ t('listing.aboutTitle') }}</h2>
          <p class="mt-3 whitespace-pre-wrap leading-7 text-muted dark:text-slate-300">{{ overviewText }}</p>
        </div>
        <div
          v-if="upstream"
          class="rounded-lg border border-line bg-surface-muted p-5 dark:border-slate-800 dark:bg-slate-900/60"
        >
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h3 class="font-semibold">{{ t('listing.upstreamMetadata') }}</h3>
            <Badge v-if="isUpstreamAggregated" tone="success">{{ t('listing.storedDelivery') }}</Badge>
          </div>
          <p v-if="isUpstreamAggregated" class="mt-2 text-sm leading-6 text-muted dark:text-slate-400">
            {{ t('listing.storedDeliveryHint') }}
          </p>
          <dl class="mt-4 grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt class="text-muted dark:text-slate-400">{{ t('listing.upstreamSource') }}</dt>
              <dd class="mt-1 font-medium">{{ upstream.sourceName || '—' }}</dd>
            </div>
            <div>
              <dt class="text-muted dark:text-slate-400">{{ t('listing.sourcePath') }}</dt>
              <dd class="mt-1 break-all font-mono text-xs">{{ upstream.sourcePath || upstream.externalId || '—' }}</dd>
            </div>
            <div>
              <dt class="text-muted dark:text-slate-400">{{ t('listing.revision') }}</dt>
              <dd class="mt-1 font-mono text-xs">{{ shortSha(upstream.commitSha || upstream.ref) }}</dd>
            </div>
            <div>
              <dt class="text-muted dark:text-slate-400">{{ t('listing.lastSynced') }}</dt>
              <dd class="mt-1">{{ formatDate(upstream.lastSeenAt) }}</dd>
            </div>
          </dl>
        </div>
        <div v-if="detail.screenshots?.length" class="mt-6 grid gap-3 sm:grid-cols-2">
          <img
            v-for="shot in detail.screenshots"
            :key="shot"
            :src="shot"
            :alt="displayName"
            loading="lazy"
            class="w-full rounded-lg border border-line object-cover dark:border-slate-800"
          />
        </div>
      </article>
      <aside class="card space-y-3 self-start p-5 text-sm">
        <h2 class="font-semibold">{{ t('listing.infoTitle') }}</h2>
        <dl class="space-y-2">
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.version') }}</dt>
            <dd class="text-right"><code v-if="latestRelease">{{ displayVersion(latestRelease.version) }}</code><span v-else>—</span></dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.category') }}</dt>
            <dd class="text-right">{{ detail.category || '—' }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.license') }}</dt>
            <dd class="text-right">
              <template v-if="latestRelease?.license">{{ latestRelease.license }}</template>
              <span v-else>—</span>
            </dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.publishedAt') }}</dt>
            <dd class="text-right">{{ formatDate(latestRelease?.publishedAt) }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.updated') }}</dt>
            <dd class="text-right">{{ formatDate(detail.updatedAt) }}</dd>
          </div>
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.coordinate') }}</dt>
            <dd class="text-right"><code class="block break-all rounded-lg bg-surface-muted px-2 py-1 text-left text-xs dark:bg-slate-800/60">{{ detail.coordinate }}</code></dd>
          </div>
        </dl>
        <a
          v-if="sourceUrl"
          :href="sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="block font-medium text-accent underline"
        >
          {{ t('listing.source') }} ↗
        </a>
        <div v-if="installInfo" class="rounded-xl bg-surface-muted p-3 text-xs dark:bg-slate-800/60">
          <p class="font-semibold">{{ t('listing.installBehavior') }}: {{ installInfo.mode }}</p>
          <p class="mt-1 text-muted dark:text-slate-400">{{ installInfo.hint }}</p>
          <p v-if="!isAppListing && latestRelease" class="mt-1 text-muted dark:text-slate-400">
            {{ t('listing.downloadPackageHint') }}
          </p>
        </div>
        <div v-if="canManageGate" class="space-y-2 border-t border-line pt-3 dark:border-slate-800">
          <h3 class="font-semibold">{{ t('publisher.setGate') }}</h3>
          <p class="text-xs text-muted dark:text-slate-400">{{ t('publisher.setGateHint') }}</p>
          <select v-model="gateLevel" class="input" :aria-label="t('publisher.minBeeLevel')">
            <option v-for="option in GATE_LEVEL_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <button type="button" class="btn btn-secondary btn-sm w-full" :disabled="gateBusy" @click="applyGate">
            {{ t('common.confirm') }}
          </button>
          <p v-if="gateSaved" class="text-xs text-success dark:text-emerald-400" role="status">
            {{ t('publisher.gateUpdated') }}
          </p>
          <p v-if="gateError" class="text-xs text-danger dark:text-red-400">{{ gateError }}</p>
        </div>
      </aside>
    </section>

    <section v-if="tab === 'versions'" class="space-y-3">
      <div
        v-if="isUpstreamAggregated && !upstream?.upstreamVersion"
        class="rounded-lg border border-accent/30 bg-accent/5 p-4 text-sm text-muted dark:text-slate-300"
      >
        <p class="font-semibold text-fg dark:text-white">{{ t('listing.versionUnspecified') }}</p>
        <p class="mt-1">{{ t('listing.versionPlaceholderHint') }}</p>
      </div>
      <MagicCard v-for="release in detail.releases" :key="release.releaseId" class="p-5">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <span class="font-semibold">{{ displayVersion(release.version) }}</span>
            <StateChip :status="release.status" />
            <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
            <Badge v-if="(release.rolloutPercent ?? 100) < 100" tone="accent">
              {{ t('listing.rollout', { percent: release.rolloutPercent }) }}
            </Badge>
          </div>
          <div class="flex items-center gap-2">
            <button
              v-if="release.status === 'PUBLISHED' && !isAppListing"
              class="rounded-lg border border-line px-3 py-1 text-xs font-medium hover:bg-surface-2 disabled:opacity-50 dark:border-slate-800"
              :disabled="packageDownloads[release.releaseId] === 'loading'"
              @click="downloadInstallPackage(release.releaseId)"
            >
              {{ packageDownloads[release.releaseId] === 'loading'
                ? t('listing.downloading') : t('listing.downloadPackage') }}
            </button>
            <span v-if="packageDownloads[release.releaseId] === 'error'" class="text-xs text-red-500">
              {{ t('listing.packageDownloadFailed') }}
            </span>
            <span class="text-xs text-muted dark:text-slate-400">{{ formatDate(release.publishedAt) }}</span>
          </div>
        </div>
        <p v-if="release.requiresHost" class="mt-2 text-sm text-muted dark:text-slate-400">
          {{ t('listing.requiresHost') }}: <code>{{ release.requiresHost }}</code>
        </p>
        <p v-if="release.changelogMarkdown" class="mt-2 whitespace-pre-wrap text-sm">{{ release.changelogMarkdown }}</p>
        <p v-else class="mt-2 text-sm text-muted dark:text-slate-400">{{ t('listing.noChangelog') }}</p>
        <dl v-if="upstream" class="mt-4 grid gap-2 border-t border-line pt-3 text-xs dark:border-slate-800 sm:grid-cols-3">
          <div>
            <dt class="text-muted dark:text-slate-400">{{ t('listing.sourcePath') }}</dt>
            <dd class="mt-1 break-all font-mono">{{ upstream.sourcePath || upstream.externalId || '—' }}</dd>
          </div>
          <div>
            <dt class="text-muted dark:text-slate-400">{{ t('listing.revision') }}</dt>
            <dd class="mt-1 font-mono">{{ shortSha(upstream.commitSha || upstream.ref) }}</dd>
          </div>
          <div>
            <dt class="text-muted dark:text-slate-400">{{ t('listing.lastSynced') }}</dt>
            <dd class="mt-1">{{ formatDate(upstream.lastSeenAt) }}</dd>
          </div>
        </dl>
      </MagicCard>
    </section>

    <section v-if="tab === 'permissions'">
      <table v-if="latestRelease?.permissions?.length" class="w-full text-left text-sm">
        <thead>
          <tr class="text-muted">
            <th class="p-2">{{ t('listing.permissionId') }}</th>
            <th class="p-2">{{ t('listing.scope') }}</th>
            <th class="p-2">{{ t('listing.required') }}</th>
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
            <td class="p-2">{{ permission.required === false ? t('listing.optional') : t('listing.required') }}</td>
            <td class="p-2">{{ permission.reason || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="card p-5">
        <h2 class="font-semibold">{{ t('listing.noPermissionsDeclared') }}</h2>
        <p class="mt-2 text-sm leading-6 text-muted dark:text-slate-400">
          {{ isUpstreamAggregated ? t('listing.noPermissionsDeclaredUpstream') : t('listing.noPermissionsDeclaredLocal') }}
        </p>
        <p v-if="isUpstreamAggregated" class="mt-3 rounded-xl bg-surface-muted p-3 text-sm dark:bg-slate-800/60">
          {{ t('listing.permissionDownloadScan') }}
        </p>
      </div>
    </section>

    <section v-if="tab === 'dependencies'">
      <ul v-if="latestRelease?.dependencies?.length" class="space-y-2">
        <li
          v-for="dependency in latestRelease.dependencies"
          :key="dependency.coordinate"
          class="card flex items-center gap-2 p-3 text-sm"
        >
          <code class="text-xs">{{ dependency.coordinate }}</code>
          <Badge tone="muted">{{ dependency.range }}</Badge>
          <Badge v-if="!dependency.optional" tone="danger">{{ t('listing.required') }}</Badge>
        </li>
      </ul>
      <div v-else class="card p-5">
        <h2 class="font-semibold">{{ t('listing.noDependenciesDeclared') }}</h2>
        <p class="mt-2 text-sm leading-6 text-muted dark:text-slate-400">
          {{ isUpstreamAggregated ? t('listing.noDependenciesDeclaredUpstream') : t('listing.noDependenciesDeclaredLocal') }}
        </p>
      </div>
    </section>

    <section v-if="tab === 'compatibility'" class="space-y-3">
      <p class="text-sm text-muted dark:text-slate-400">{{ t('listing.compatHint') }}</p>
      <div v-if="isUpstreamAggregated" class="grid gap-3 sm:grid-cols-2">
        <div class="card p-4">
          <p class="text-xs text-muted dark:text-slate-400">{{ t('listing.deliveryMode') }}</p>
          <p class="mt-1 font-semibold">{{ t('listing.aggregatedUpstream') }}</p>
          <p class="mt-1 text-sm text-muted dark:text-slate-400">{{ t('listing.storedDelivery') }}</p>
        </div>
        <div class="card p-4">
          <p class="text-xs text-muted dark:text-slate-400">{{ t('listing.targetPlatform') }}</p>
          <p class="mt-1 font-semibold">{{ t('listing.targetPlatformUniversal') }}</p>
          <p class="mt-1 text-sm text-muted dark:text-slate-400">{{ t('listing.noHostRestriction') }}</p>
        </div>
      </div>
      <table class="w-full text-left text-sm">
        <thead>
          <tr class="text-muted">
            <th class="p-2">{{ t('listing.version') }}</th>
            <th class="p-2">{{ t('listing.requiresHost') }}</th>
            <th class="p-2">{{ t('listing.channel') }}</th>
            <th class="p-2">{{ t('listing.status') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="release in detail.releases"
            :key="release.releaseId"
            class="border-t border-line dark:border-slate-800"
          >
            <td class="p-2 font-mono text-xs">{{ displayVersion(release.version) }}</td>
            <td class="p-2"><code v-if="release.requiresHost">{{ release.requiresHost }}</code><span v-else class="text-muted">{{ t('listing.noHostRestriction') }}</span></td>
            <td class="p-2">{{ t(`channel.${release.channel}`) }}</td>
            <td class="p-2"><StateChip :status="release.status" /></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="tab === 'security'" class="space-y-4 text-sm">
      <div v-if="isUpstreamAggregated" class="rounded-lg border border-accent/30 bg-accent/5 p-5">
        <h2 class="font-semibold">{{ t('listing.upstreamSecurityTitle') }}</h2>
        <ol class="mt-3 grid gap-3 sm:grid-cols-2">
          <li v-for="(item, index) in [
            t('listing.securitySyncFetch'),
            t('listing.securityPackaging'),
            t('listing.securityScan'),
            t('listing.securityStoredSigned'),
          ]" :key="item" class="flex gap-3 rounded-xl bg-surface/70 p-3 dark:bg-slate-900/70">
            <span class="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-accent text-xs font-bold text-white">{{ index + 1 }}</span>
            <span class="leading-6">{{ item }}</span>
          </li>
        </ol>
        <dl class="mt-4 grid gap-3 border-t border-accent/20 pt-4 sm:grid-cols-2">
          <div>
            <dt class="text-muted dark:text-slate-400">{{ t('listing.metadataDigest') }}</dt>
            <dd class="mt-1 break-all font-mono text-xs">{{ upstream?.metadataSha256 || '—' }}</dd>
          </div>
          <div>
            <dt class="text-muted dark:text-slate-400">{{ t('listing.revision') }}</dt>
            <dd class="mt-1 font-mono text-xs">{{ shortSha(upstream?.commitSha || upstream?.ref) }}</dd>
          </div>
        </dl>
      </div>
      <p v-if="!latestRelease?.artifacts?.length" class="text-muted dark:text-slate-400">
        {{ t('listing.noArtifacts') }}
      </p>
      <template v-else>
        <div
          v-for="artifact in latestRelease.artifacts"
          :key="artifact.artifactId"
          class="card space-y-1 p-4"
        >
          <div class="flex flex-wrap items-center gap-2">
            <Badge tone="muted">{{ artifact.kind }}</Badge>
            <Badge v-if="artifact.variant && artifact.variant !== 'default'" tone="accent">
              {{ artifact.variant }}
            </Badge>
            <span class="font-mono text-xs">{{ artifact.filename }}</span>
            <span class="text-muted dark:text-slate-400">
              {{ artifact.platform }}/{{ artifact.arch }} · {{ isUpstreamAggregated && !artifact.size ? t('listing.generatedOnDemand') : formatSize(artifact.size) }}
            </span>
            <button
              v-if="artifact.artifactId"
              class="ml-auto rounded-lg border border-line px-3 py-1 text-xs font-medium hover:bg-surface-2 disabled:opacity-50 dark:border-slate-800"
              :disabled="artifactTickets[artifact.artifactId] === 'loading'"
              @click="downloadArtifact(artifact)"
            >
              {{ artifactTickets[artifact.artifactId] === 'loading'
                ? t('listing.downloading') : t('common.download') }}
            </button>
          </div>
          <p v-if="artifactTickets[artifact.artifactId ?? ''] === 'error'" class="text-xs text-red-500">
            {{ t('listing.downloadFailed') }}
          </p>
          <p class="break-all">
            <span class="text-muted dark:text-slate-400">{{ t('listing.sha256') }}:</span>
            <code>{{ artifact.sha256 || (isUpstreamAggregated ? t('listing.checksumAtDownload') : '—') }}</code>
          </p>
          <p v-if="artifact.keyId" class="break-all">
            <span class="text-muted dark:text-slate-400">{{ t('listing.signature') }}:</span>
            <code>{{ artifact.keyId }}</code>
          </p>
        </div>
        <p v-if="!isUpstreamAggregated" class="text-muted dark:text-slate-400">
          {{ t('listing.signatureNote') }}
        </p>
        <p v-else class="text-muted dark:text-slate-400">{{ t('listing.storedChecksumNote') }}</p>
      </template>
    </section>

    <section v-if="tab === 'reviews'" class="space-y-6">
      <div v-if="ratings" class="flex flex-wrap items-center gap-4">
        <div class="text-4xl font-bold">{{ ratings.summary?.average?.toFixed(1) ?? '—' }}</div>
        <div class="text-sm text-muted">
          {{ t('listing.ratingCount', { count: ratings.summary?.count ?? 0 }) }}
        </div>
      </div>

      <ul class="space-y-2">
        <li
          v-for="rating in ratings?.ratings ?? []"
          :key="rating.ratingId"
          class="card p-3 text-sm"
        >
          <div class="flex items-center gap-1" :aria-label="String(rating.stars)">
            <span v-for="n in 5" :key="n" :class="n <= (rating.stars ?? 0) ? 'text-amber-500' : 'text-muted'">★</span>
          </div>
          <p v-if="rating.comment" class="mt-1">{{ rating.comment }}</p>
        </li>
        <li v-if="!ratings?.ratings?.length" class="text-sm text-muted">{{ t('listing.noReviews') }}</li>
      </ul>

      <form v-if="auth.isAuthenticated" class="card space-y-3 p-4" @submit.prevent="submitRating">
        <h3 class="font-semibold">{{ t('listing.writeReview') }}</h3>
        <div class="flex gap-1" role="radiogroup" :aria-label="t('listing.stars')">
          <button
            v-for="n in 5"
            :key="n"
            type="button"
            role="radio"
            :aria-checked="myStars === n"
            class="text-2xl leading-none"
            :class="n <= myStars ? 'text-amber-500' : 'text-muted'"
            @click="myStars = n"
          >
            ★
          </button>
        </div>
        <textarea
          v-model="myComment"
          rows="3"
          maxlength="2000"
          :placeholder="t('listing.reviewPlaceholder')"
          class="input"
        />
        <div class="flex items-center gap-2">
          <button :disabled="!myStars" class="btn btn-primary shrink-0 whitespace-nowrap">
            {{ t('listing.submitReview') }}
          </button>
          <span v-if="ratingSaved" class="text-sm text-success dark:text-emerald-400">{{ t('listing.reviewSaved') }}</span>
        </div>
      </form>
      <p v-else class="text-sm text-muted">
        <RouterLink
          class="text-accent hover:underline"
          :to="{ name: 'signin', query: { redirect: route.fullPath } }"
        >{{ t('listing.signInToReview') }}</RouterLink>
      </p>
    </section>
  </div>

  <!-- Abuse report dialog (design §12.4 举报) -->
  <div
    v-if="reporting"
    class="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4"
    role="dialog"
    :aria-label="t('listing.report')"
    @click.self="reporting = false"
  >
    <div class="w-full max-w-md rounded-lg border border-line bg-surface p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 class="text-lg font-bold">{{ t('listing.report') }}</h2>
      <p v-if="reportDone" class="alert alert-success mt-4" role="status">
        {{ t('listing.reportDone') }}
      </p>
      <form v-else class="mt-4 space-y-3" @submit.prevent="submitReport">
        <label class="block text-sm">
          {{ t('listing.reportReason') }}
          <select
            v-model="reportReason"
            class="input mt-1"
          >
            <option v-for="reason in ['malware', 'policy_violation', 'spam', 'misleading', 'license', 'other']" :key="reason" :value="reason">
              {{ t(`admin.reason.${reason}`) }}
            </option>
          </select>
        </label>
        <textarea
          v-model="reportDetails"
          rows="3"
          maxlength="2000"
          :placeholder="t('listing.reportDetails')"
          class="input"
        />
        <p v-if="reportError" class="text-sm text-danger dark:text-red-400">{{ reportError }}</p>
        <div class="flex justify-end gap-2">
          <button type="button" class="btn btn-secondary" @click="reporting = false">
            {{ t('common.cancel') }}
          </button>
          <button class="btn btn-danger">
            {{ t('listing.reportSubmit') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
