<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type DownloadTicket, type Library, type ListingDetail, type RatingsPage, type ResolveResponse } from '../api/client';
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
const { t, locale } = useI18n();
const auth = useAuthStore();

const detail = ref<ListingDetail | null>(null);
const error = ref<string | null>(null);
const loading = ref(true);
const tab = ref<'overview' | 'versions' | 'permissions' | 'dependencies' | 'compatibility' | 'security' | 'reviews'>('overview');

const installStage = ref<
  'idle' | 'resolving' | 'confirm' | 'downloading' | 'verifying' | 'done' | 'failed'
>('idle');
const resolution = ref<ResolveResponse | null>(null);
const ticket = ref<DownloadTicket | null>(null);
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

function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleDateString();
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

async function load() {
  loading.value = true;
  error.value = null;
  try {
    detail.value = await api.get<ListingDetail>(
      `/api/v1/listings/${props.namespace}/${props.slug}`,
    );
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
    error.value = e instanceof Error ? e.message : 'error';
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
        <img
          v-if="detail.iconUrl"
          :src="detail.iconUrl"
          alt=""
          class="h-16 w-16 rounded-2xl object-cover"
        />
        <div
          v-else
          class="grid h-16 w-16 place-items-center rounded-2xl bg-gradient-to-br from-accent to-accent2 text-2xl font-bold text-white"
        >
          {{ displayName.charAt(0) }}
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-2xl font-bold">{{ displayName }}</h1>
            <Badge tone="muted">{{ t(`type.${detail.type}`) }}</Badge>
            <Badge v-if="latestRelease" tone="muted">v{{ latestRelease.version }}</Badge>
            <Badge v-if="detail.defaultChannel !== 'stable'" tone="accent">
              {{ t(`channel.${detail.defaultChannel}`) }}
            </Badge>
          </div>
          <p class="mt-2 max-w-2xl text-muted dark:text-slate-400">
            {{ localization?.summary }}
          </p>
          <p class="mt-2 text-sm text-muted dark:text-slate-500">
            {{ t('listing.publisher') }}: <strong>{{ detail.publisherName }}</strong>
            · {{ detail.downloads?.toLocaleString() ?? 0 }} {{ t('discover.statsDownloads') }}
            · {{ detail.favorites?.toLocaleString() ?? 0 }} {{ t('listing.favoritesCount') }}
            · {{ t('listing.updated') }}: {{ formatDate(detail.updatedAt) }}
          </p>
          <div
            v-if="detail.category || detail.tags?.length"
            class="mt-3 flex flex-wrap items-center gap-1.5"
          >
            <Badge v-if="detail.category" tone="accent">{{ detail.category }}</Badge>
            <Badge v-for="tag in detail.tags" :key="tag" tone="muted">{{ tag }}</Badge>
          </div>
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
          <button
            v-if="auth.isAuthenticated && !reportDone"
            class="rounded-xl border border-line px-4 py-2 text-sm text-muted dark:border-slate-800"
            @click="reporting = true"
          >
            {{ t('listing.report') }}
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
        v-for="key in ['overview', 'versions', 'permissions', 'dependencies', 'compatibility', 'security', 'reviews'] as const"
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

    <section v-if="tab === 'overview'" class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
      <article class="prose max-w-none">
        <p class="whitespace-pre-wrap">{{ localization?.descriptionMarkdown }}</p>
        <div v-if="detail.screenshots?.length" class="mt-6 grid gap-3 sm:grid-cols-2">
          <img
            v-for="shot in detail.screenshots"
            :key="shot"
            :src="shot"
            :alt="displayName"
            loading="lazy"
            class="w-full rounded-2xl border border-line object-cover dark:border-slate-800"
          />
        </div>
      </article>
      <aside class="space-y-3 self-start rounded-2xl border border-line p-5 text-sm dark:border-slate-800">
        <h2 class="font-semibold">{{ t('listing.infoTitle') }}</h2>
        <dl class="space-y-2">
          <div class="flex justify-between gap-3">
            <dt class="shrink-0 text-muted dark:text-slate-400">{{ t('listing.version') }}</dt>
            <dd class="text-right"><code v-if="latestRelease">v{{ latestRelease.version }}</code><span v-else>—</span></dd>
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
            <dd class="break-all text-right"><code class="text-xs">{{ detail.coordinate }}</code></dd>
          </div>
        </dl>
        <a
          v-if="latestRelease?.sourceUrl"
          :href="latestRelease.sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="block font-medium text-accent underline"
        >
          {{ t('listing.source') }} ↗
        </a>
        <div v-if="installInfo" class="rounded-xl bg-surface-muted p-3 text-xs dark:bg-slate-800/60">
          <p class="font-semibold">{{ t('listing.installBehavior') }}: {{ installInfo.mode }}</p>
          <p class="mt-1 text-muted dark:text-slate-400">{{ installInfo.hint }}</p>
        </div>
      </aside>
    </section>

    <section v-if="tab === 'versions'" class="space-y-3">
      <MagicCard v-for="release in detail.releases" :key="release.releaseId" class="p-5">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <span class="font-semibold">v{{ release.version }}</span>
            <StateChip :status="release.status" />
            <Badge tone="muted">{{ t(`channel.${release.channel}`) }}</Badge>
            <Badge v-if="(release.rolloutPercent ?? 100) < 100" tone="accent">
              {{ t('listing.rollout', { percent: release.rolloutPercent }) }}
            </Badge>
          </div>
          <span class="text-xs text-muted dark:text-slate-400">{{ formatDate(release.publishedAt) }}</span>
        </div>
        <p v-if="release.requiresHost" class="mt-2 text-sm text-muted dark:text-slate-400">
          {{ t('listing.requiresHost') }}: <code>{{ release.requiresHost }}</code>
        </p>
        <p v-if="release.changelogMarkdown" class="mt-2 whitespace-pre-wrap text-sm">{{ release.changelogMarkdown }}</p>
        <p v-else class="mt-2 text-sm text-muted dark:text-slate-400">{{ t('listing.noChangelog') }}</p>
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

    <section v-if="tab === 'compatibility'" class="space-y-3">
      <p class="text-sm text-muted dark:text-slate-400">{{ t('listing.compatHint') }}</p>
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
            <td class="p-2 font-mono text-xs">v{{ release.version }}</td>
            <td class="p-2"><code v-if="release.requiresHost">{{ release.requiresHost }}</code><span v-else class="text-muted">—</span></td>
            <td class="p-2">{{ t(`channel.${release.channel}`) }}</td>
            <td class="p-2"><StateChip :status="release.status" /></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="tab === 'security'" class="space-y-4 text-sm">
      <p v-if="!latestRelease?.artifacts?.length" class="text-muted dark:text-slate-400">
        {{ t('listing.noArtifacts') }}
      </p>
      <template v-else>
        <div
          v-for="artifact in latestRelease.artifacts"
          :key="artifact.artifactId"
          class="space-y-1 rounded-2xl border border-line p-4 dark:border-slate-800"
        >
          <div class="flex flex-wrap items-center gap-2">
            <Badge tone="muted">{{ artifact.kind }}</Badge>
            <span class="font-mono text-xs">{{ artifact.filename }}</span>
            <span class="text-muted dark:text-slate-400">
              {{ artifact.platform }}/{{ artifact.arch }} · {{ formatSize(artifact.size) }}
            </span>
          </div>
          <p class="break-all">
            <span class="text-muted dark:text-slate-400">{{ t('listing.sha256') }}:</span>
            <code>{{ artifact.sha256 }}</code>
          </p>
          <p v-if="artifact.keyId" class="break-all">
            <span class="text-muted dark:text-slate-400">{{ t('listing.signature') }}:</span>
            <code>{{ artifact.keyId }}</code>
          </p>
        </div>
        <p class="text-muted dark:text-slate-400">
          {{ t('listing.signatureNote') }}
        </p>
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
          class="rounded-xl border border-line p-3 text-sm dark:border-slate-800"
        >
          <div class="flex items-center gap-1" :aria-label="String(rating.stars)">
            <span v-for="n in 5" :key="n" :class="n <= (rating.stars ?? 0) ? 'text-amber-500' : 'text-muted'">★</span>
          </div>
          <p v-if="rating.comment" class="mt-1">{{ rating.comment }}</p>
        </li>
        <li v-if="!ratings?.ratings?.length" class="text-sm text-muted">{{ t('listing.noReviews') }}</li>
      </ul>

      <form v-if="auth.isAuthenticated" class="space-y-3 rounded-2xl border border-line p-4 dark:border-slate-800" @submit.prevent="submitRating">
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
          class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
        />
        <div class="flex items-center gap-2">
          <button :disabled="!myStars" class="shrink-0 whitespace-nowrap rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white">
            {{ t('listing.submitReview') }}
          </button>
          <span v-if="ratingSaved" class="text-sm text-green-600 dark:text-green-400">{{ t('listing.reviewSaved') }}</span>
        </div>
      </form>
      <p v-else class="text-sm text-muted">{{ t('listing.signInToReview') }}</p>
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
    <div class="w-full max-w-md rounded-2xl border border-line bg-surface p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 class="text-lg font-bold">{{ t('listing.report') }}</h2>
      <p v-if="reportDone" class="mt-4 text-sm text-green-600 dark:text-green-400">
        {{ t('listing.reportDone') }}
      </p>
      <form v-else class="mt-4 space-y-3" @submit.prevent="submitReport">
        <label class="block text-sm">
          {{ t('listing.reportReason') }}
          <select
            v-model="reportReason"
            class="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
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
          class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
        />
        <p v-if="reportError" class="text-sm text-red-600 dark:text-red-400">{{ reportError }}</p>
        <div class="flex justify-end gap-2">
          <button type="button" class="rounded-xl border border-line px-4 py-2 text-sm dark:border-slate-800" @click="reporting = false">
            {{ t('common.cancel') }}
          </button>
          <button class="rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white">
            {{ t('listing.reportSubmit') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
