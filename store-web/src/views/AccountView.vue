<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  api,
  type Library,
  type MembershipStatus,
  type PublicUser,
} from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import BeeCrest from '../components/BeeCrest.vue';
import EmptyState from '../components/EmptyState.vue';
import ErrorState from '../components/ErrorState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import PageHeader from '../components/PageHeader.vue';
import { formatDate, formatDateTime } from '../utils/format';
import { useAuthStore } from '../stores/auth';
import { beeMark } from '../bee-levels';

/**
 * User Center (用户中心): one signed-in landing page aggregating identity,
 * the current Infinia Level with the purchased-membership status and its
 * upgrade entry, account details (display name + password), library and
 * organization summaries, role-aware quick links, and sign-in sessions &
 * devices. The full ladder lives on /membership — here only the viewer's own
 * position shows.
 *
 * The overview is the page's hero ("hive passport"): hexagon identity mark,
 * display-size level statement tinted by the tier, and the membership pill.
 * Everything below stays quiet utility.
 */
const { t } = useI18n();
const auth = useAuthStore();

const user = ref<PublicUser | null>(null);
const library = ref<Library | null>(null);
const membership = ref<MembershipStatus | null>(null);
const organizations = ref<{ organizationId?: string; slug?: string; name?: string }[]>([]);
const sessions = ref<{ sessionId: string; clientId: string; kind: string; createdAt: string }[]>([]);
const devices = ref<
  { deviceId: string; name: string; platform: string; revoked: boolean }[]
>([]);
const loading = ref(true);
const error = ref<string | null>(null);

// ---- profile editing ----
const displayNameDraft = ref('');
const savingProfile = ref(false);
const profileMessage = ref<string | null>(null);
const profileError = ref<string | null>(null);

// ---- password ----
const currentPassword = ref('');
const newPassword = ref('');
const passwordMessage = ref<string | null>(null);
const passwordError = ref<string | null>(null);

const beeLevel = computed(() => user.value?.beeLevel ?? 0);
/**
 * What the store enforces: max(base, active purchased membership). The
 * membership-status payload is the live source (freshly computed per request);
 * /me's effectiveBeeLevel is the fallback for the moment it is still loading.
 */
const effectiveLevel = computed(() =>
  membership.value?.effectiveBeeLevel ?? user.value?.effectiveBeeLevel ?? beeLevel.value);
const nextLevel = computed(() => (effectiveLevel.value < 4 ? effectiveLevel.value + 1 : null));
const hasMembership = computed(() => membership.value?.membershipLevel != null);
/** An upgrade makes sense while the ladder has room or a renewal is possible. */
const canUpgrade = computed(() => effectiveLevel.value < 4 || hasMembership.value);

/** The tier's own color drives the hero's hex wash and the level numeral. */
const tierHex = computed(() => {
  const level = effectiveLevel.value;
  if (level >= 4) return '#a73afd'; // queen — the gradient is spoken by the avatar
  return beeMark(level).hex || '#6c707e'; // larva has no fill; fall back to muted ink
});

const tierTextClass = computed(() => {
  switch (beeMark(effectiveLevel.value).tier) {
    case 'worker':
      return 'text-accent';
    case 'forager':
      return 'text-success';
    case 'guard':
      return 'text-warning';
    case 'queen':
      return 'bg-gradient-to-r from-jb-orange via-accent2 to-jb-purple bg-clip-text text-transparent';
    default:
      return 'text-ink';
  }
});

const userInitial = computed(() =>
  (user.value?.displayName ?? user.value?.email ?? '?').charAt(0).toUpperCase());

const quickLinks = computed(() => {
  const links: { to: string; label: string }[] = [
    { to: '/library', label: t('nav.library') },
    { to: '/membership', label: t('account.membershipEntry') },
    { to: '/organizations', label: t('nav.organizations') },
  ];
  if (auth.roles.some((r) => ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'].includes(r))) {
    links.unshift({ to: '/publisher', label: t('nav.publisher') });
  }
  if (auth.roles.includes('PLATFORM_ADMIN')) {
    links.unshift({ to: '/admin', label: t('nav.admin') });
  }
  return links;
});

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [me, lib, activeSessions, activeDevices, membershipStatus] = await Promise.all([
      api.get<PublicUser>('/api/v1/me'),
      api.get<Library>('/api/v1/me/library'),
      api.get<{ sessionId: string; clientId: string; kind: string; createdAt: string }[]>(
        '/api/v1/me/sessions',
      ),
      api.get<{ deviceId: string; name: string; platform: string; revoked: boolean }[]>(
        '/api/v1/me/devices',
      ),
      api.getMembershipStatus(),
    ]);
    user.value = me;
    displayNameDraft.value = me.displayName;
    library.value = lib;
    sessions.value = activeSessions;
    devices.value = activeDevices;
    membership.value = membershipStatus;
    try {
      organizations.value = await api.get('/api/v1/organizations');
    } catch {
      organizations.value = []; // memberships are optional context, never fatal
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('common.error');
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function saveProfile() {
  const name = displayNameDraft.value.trim();
  if (!name || name === user.value?.displayName) return;
  savingProfile.value = true;
  profileMessage.value = null;
  profileError.value = null;
  try {
    await api.put('/api/v1/me', { displayName: name });
    if (user.value) user.value.displayName = name;
    if (auth.user) auth.user = { ...auth.user, displayName: name };
    profileMessage.value = t('account.profileSaved');
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : t('common.error');
  } finally {
    savingProfile.value = false;
  }
}

async function revokeSession(sessionId: string) {
  await api.delete(`/api/v1/me/sessions/${sessionId}`);
  sessions.value = sessions.value.filter((s) => s.sessionId !== sessionId);
}

async function revokeDevice(deviceId: string) {
  await api.delete(`/api/v1/me/devices/${deviceId}`);
  devices.value = devices.value.map((d) =>
    d.deviceId === deviceId ? { ...d, revoked: true } : d,
  );
}

async function changePassword() {
  passwordMessage.value = null;
  passwordError.value = null;
  try {
    await api.put('/api/v1/me/password', {
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    });
    passwordMessage.value = t('account.passwordChanged');
    currentPassword.value = '';
    newPassword.value = '';
  } catch (e) {
    passwordError.value =
      e && typeof e === 'object' && 'detail' in e
        ? String((e as { detail?: string }).detail)
        : t('common.error');
  }
}

function listingRoute(coordinate: string) {
  const parts = coordinate.replace('infinia://', '').split('/');
  return parts.length >= 3 ? `/listing/${parts[1]}/${parts[2]}` : '/browse';
}
</script>

<template>
  <div class="space-y-6">
    <PageHeader :title="t('account.title')" />
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else-if="user">
      <!-- Hero — the hive passport: identity cell, level statement, membership.
           The tier-tinted hex cluster is the page's one ornamental act. -->
      <MagicCard class="hive-hero relative overflow-hidden p-5 sm:p-6">
        <svg
          class="hive-hives pointer-events-none absolute -right-6 -top-10 hidden sm:block"
          width="240"
          height="240"
          viewBox="0 0 240 240"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M150 8 208 41v66l-58 33-58-33V41L150 8Z"
            :stroke="tierHex"
            stroke-width="1.5"
            opacity=".16"
          />
          <path
            d="M88 96 128 119v46l-40 23-40-23v-46l40-23Z"
            :stroke="tierHex"
            stroke-width="1.5"
            opacity=".24"
          />
          <path
            d="M170 138 196 153v30l-26 15-26-15v-30l26-15Z"
            :stroke="tierHex"
            stroke-width="1.5"
            opacity=".1"
          />
          <path d="M88 119v23l20 11.5v-23L88 119Z" :fill="tierHex" opacity=".14" />
        </svg>

        <div class="relative flex flex-wrap items-start gap-6">
          <!-- Identity cell: the avatar is a honeycomb hexagon, brand-filled. -->
          <div class="hive-avatar shrink-0" aria-hidden="true">
            <span class="hive-avatar__initial">{{ userInitial }}</span>
          </div>

          <div class="min-w-0 flex-1">
            <h2 class="text-xl font-bold">{{ user.displayName }}</h2>
            <p class="text-sm text-muted">{{ user.email }}</p>

            <!-- Level statement: display type, tier-tinted crest and numeral. -->
            <div class="mt-3 flex items-center gap-3">
              <span class="shrink-0" :class="tierTextClass">
                <BeeCrest :level="effectiveLevel" :size="36" />
              </span>
              <div>
                <p class="flex items-baseline gap-2">
                  <span class="text-[1.5rem] font-bold leading-none tracking-tight">
                    {{ t(`beeLevel.${effectiveLevel}`) }}
                  </span>
                  <span
                    class="text-[1.5rem] font-bold leading-none tabular-nums"
                    :class="tierTextClass"
                  >Lv{{ effectiveLevel }}</span>
                </p>
                <p class="mt-1 text-xs tracking-wide text-muted">
                  {{ t('beeLevel.title') }}
                  <template v-if="nextLevel !== null">
                    · {{ t('account.levelNext', { next: t(`beeLevel.${nextLevel}`) }) }}
                  </template>
                  <template v-else>· {{ t('account.levelTop') }}</template>
                </p>
              </div>
            </div>

            <!-- Membership pill: status plus the purchase/renew CTA. -->
            <div
              v-if="canUpgrade"
              class="mt-3 inline-flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-accent/30 bg-accent/5 px-3.5 py-2"
              data-testid="account-membership-card"
            >
              <span
                v-if="hasMembership"
                class="inline-flex items-center gap-2 text-sm"
                data-testid="account-membership-active"
              >
                <span class="hive-dot" :style="{ background: tierHex }" aria-hidden="true" />
                {{ t('membership.activeMembership', { level: membership?.membershipLevel }) }}
                <span class="text-muted">
                  {{ t('account.memberUntil', { date: formatDate(membership?.membershipExpiresAt ?? '') }) }}
                </span>
              </span>
              <span v-else class="text-sm text-muted">
                {{ t('account.membershipCardHint') }}
              </span>
              <RouterLink
                to="/membership"
                class="btn btn-primary btn-sm shrink-0"
                data-testid="account-membership-cta"
              >
                {{ hasMembership ? t('membership.renew') : t('account.membershipCta') }}
              </RouterLink>
            </div>
          </div>

          <div class="flex w-full flex-col gap-1.5 sm:w-44">
            <dt class="text-xs tracking-wider text-muted">{{ t('account.roles') }}</dt>
            <dd class="flex flex-wrap gap-1">
              <Badge v-for="role in user.roles" :key="role" tone="accent">
                {{ t(`role.${role}`) }}
              </Badge>
            </dd>
            <dt class="mt-1.5 text-xs tracking-wider text-muted">{{ t('account.quickLinks') }}</dt>
            <dd class="flex flex-col gap-1">
              <RouterLink
                v-for="link in quickLinks"
                :key="link.to"
                :to="link.to"
                class="btn btn-secondary w-full"
              >
                {{ link.label }}
              </RouterLink>
            </dd>
          </div>
        </div>
      </MagicCard>

      <!-- Utility grid: every card earns its place — account (name + password),
           library, organizations, sign-in sessions/devices. -->
      <div class="grid gap-4 lg:grid-cols-2 lg:gap-5">
        <!-- Account details: display name and password live together. -->
        <MagicCard class="p-5">
          <h2 class="mb-3 font-semibold">{{ t('account.editProfile') }}</h2>
          <form class="flex flex-col gap-2 sm:flex-row sm:items-end" @submit.prevent="saveProfile">
            <label class="w-full text-sm">
              {{ t('account.displayName') }}
              <input
                v-model="displayNameDraft"
                required
                minlength="1"
                maxlength="64"
                class="input mt-1"
              />
            </label>
            <button
              :disabled="savingProfile || displayNameDraft.trim() === user.displayName"
              class="btn btn-primary shrink-0 self-end whitespace-nowrap"
            >
              {{ t('common.confirm') }}
            </button>
          </form>
          <p v-if="profileMessage" class="alert alert-success mt-2" role="status">
            {{ profileMessage }}
          </p>
          <p v-if="profileError" class="alert alert-error mt-2" role="alert">
            {{ profileError }}
          </p>

          <div class="my-4 border-t border-line dark:border-slate-800" aria-hidden="true" />
          <h3 class="mb-2 text-sm font-semibold">{{ t('account.changePassword') }}</h3>
          <form class="flex flex-col gap-2 sm:flex-row sm:items-end" @submit.prevent="changePassword">
            <label class="w-full text-sm">
              {{ t('account.currentPassword') }}
              <input
                v-model="currentPassword"
                type="password"
                required
                autocomplete="current-password"
                class="input mt-1"
              />
            </label>
            <label class="w-full text-sm">
              {{ t('account.newPassword') }}
              <input
                v-model="newPassword"
                type="password"
                required
                minlength="8"
                autocomplete="new-password"
                class="input mt-1"
              />
            </label>
            <button class="btn btn-primary shrink-0 self-end whitespace-nowrap">
              {{ t('account.changePassword') }}
            </button>
          </form>
          <p v-if="passwordMessage" class="alert alert-success mt-2" role="status">{{ passwordMessage }}</p>
          <p v-if="passwordError" class="alert alert-error mt-2" role="alert">{{ passwordError }}</p>
        </MagicCard>

        <!-- Library summary -->
        <MagicCard class="p-5">
          <div class="mb-3 flex items-center justify-between">
            <h2 class="font-semibold">{{ t('account.myLibrary') }}</h2>
            <RouterLink to="/library" class="text-sm text-accent hover:underline">
              {{ t('common.viewAll') }} →
            </RouterLink>
          </div>
          <dl class="grid grid-cols-3 gap-3 text-center">
            <div class="card p-3">
              <dd class="text-3xl font-bold tabular-nums tracking-tight">
                {{ library?.favorites?.length ?? 0 }}
              </dd>
              <dt class="mt-0.5 text-xs text-muted">{{ t('account.favoritesCount') }}</dt>
            </div>
            <div class="card p-3">
              <dd class="text-3xl font-bold tabular-nums tracking-tight">
                {{ library?.entitlements?.length ?? 0 }}
              </dd>
              <dt class="mt-0.5 text-xs text-muted">{{ t('account.entitlementsCount') }}</dt>
            </div>
            <div class="card p-3">
              <dd class="text-3xl font-bold tabular-nums tracking-tight">
                {{ library?.installHistory?.length ?? 0 }}
              </dd>
              <dt class="mt-0.5 text-xs text-muted">{{ t('account.installedCount') }}</dt>
            </div>
          </dl>
          <EmptyState
            v-if="!library?.favorites?.length"
            :title="t('account.noFavorites')"
          />
          <ul v-else class="mt-3 space-y-1 text-sm">
            <li
              v-for="favorite in library.favorites.slice(0, 3)"
              :key="favorite.listingCoordinate"
              class="flex items-center justify-between gap-2"
            >
              <RouterLink
                :to="listingRoute(favorite.listingCoordinate ?? '')"
                class="truncate hover:text-accent"
              >
                {{ favorite.name ?? favorite.listingCoordinate }}
              </RouterLink>
              <span class="shrink-0 text-xs text-muted">{{ formatDate(favorite.addedAt) }}</span>
            </li>
          </ul>
        </MagicCard>

        <!-- Organizations summary -->
        <MagicCard class="p-5">
          <div class="mb-3 flex items-center justify-between">
            <h2 class="font-semibold">{{ t('account.myOrganizations') }}</h2>
            <RouterLink to="/organizations" class="text-sm text-accent hover:underline">
              {{ t('common.viewAll') }} →
            </RouterLink>
          </div>
          <EmptyState v-if="!organizations.length" :title="t('account.noOrganizations')" />
          <ul v-else class="flex flex-wrap gap-2">
            <li
              v-for="(org, index) in organizations"
              :key="org.organizationId ?? org.slug ?? index"
            >
              <Badge tone="muted">{{ org.name || org.slug }}</Badge>
            </li>
          </ul>
        </MagicCard>

        <!-- Sign-in sessions and devices -->
        <MagicCard class="p-5">
          <h2 class="mb-3 font-semibold">{{ t('account.signinDevices') }}</h2>

          <details>
            <summary class="cursor-pointer text-sm font-medium text-muted hover:text-accent">
              {{ t('account.sessions') }} ({{ sessions.length }})
            </summary>
            <EmptyState v-if="!sessions.length" :title="t('account.noSessions')" />
            <ul v-else class="mt-2 space-y-2 text-sm">
              <li
                v-for="session in sessions"
                :key="session.sessionId"
                class="card flex flex-wrap items-center justify-between gap-2 px-3 py-2"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <Badge tone="muted">{{ session.clientId }}</Badge>
                  <Badge tone="muted">{{ session.kind }}</Badge>
                  <span class="text-xs text-muted">{{ formatDateTime(session.createdAt) }}</span>
                </div>
                <button class="btn btn-danger-outline btn-sm" @click="revokeSession(session.sessionId)">
                  {{ t('account.revoke') }}
                </button>
              </li>
            </ul>
          </details>

          <details class="mt-2">
            <summary class="cursor-pointer text-sm font-medium text-muted hover:text-accent">
              {{ t('account.devices') }} ({{ devices.length }})
            </summary>
            <EmptyState v-if="!devices.length" :title="t('account.noDevices')" />
            <ul v-else class="mt-2 space-y-2 text-sm">
              <li
                v-for="device in devices"
                :key="device.deviceId"
                class="card flex flex-wrap items-center justify-between gap-2 px-3 py-2"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-medium">{{ device.name }}</span>
                  <Badge tone="muted">{{ device.platform }}</Badge>
                  <Badge v-if="device.revoked" tone="danger">{{ t('account.revoked') }}</Badge>
                </div>
                <button
                  v-if="!device.revoked"
                  class="btn btn-danger-outline btn-sm"
                  @click="revokeDevice(device.deviceId)"
                >
                  {{ t('account.revoke') }}
                </button>
              </li>
            </ul>
          </details>
        </MagicCard>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* The signature: the avatar is a honeycomb cell, brand-gradient filled. */
.hive-avatar {
  display: grid;
  place-items: center;
  width: 72px;
  height: 80px;
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  background: var(--hero-gradient);
}
.hive-avatar__initial {
  font-size: 1.75rem;
  font-weight: 700;
  color: #fff;
  line-height: 1;
  /* Optically center within the hexagon's usable area. */
  transform: translateY(2px);
}

/* Wax-seal dot for the active membership pill. */
.hive-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex: none;
}
</style>
