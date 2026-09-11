<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type Library, type MembershipStatus, type PublicUser } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import BeeCrest from '../components/BeeCrest.vue';
import HexCluster from '../components/HexCluster.vue';
import EmptyState from '../components/EmptyState.vue';
import ErrorState from '../components/ErrorState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import PageHeader from '../components/PageHeader.vue';
import { formatDate, formatDateTime } from '../utils/format';
import { useAuthStore } from '../stores/auth';
import { beeMark } from '../bee-levels';

/**
 * User Center (用户中心): the account-management page — identity, the current
 * Infinia Level with its membership deadline and renew action in one line,
 * account details (display name + password) and sign-in sessions & devices.
 *
 * It deliberately does NOT mirror the global navigation: 我的库 and 组织 have
 * their own destinations (library in the header nav, organizations in the
 * account menu), and the full bee ladder lives on /membership. Information
 * appears exactly once.
 *
 * The hero is the page's signature ("hive passport"): hexagon identity mark
 * and a display-size level statement tinted by the tier.
 */
const { t } = useI18n();
const auth = useAuthStore();

const user = ref<PublicUser | null>(null);
const membership = ref<MembershipStatus | null>(null);
/** Artifacts the account holds entitlements for (我的库 · 已获取). */
const artifactCount = ref(0);
const sessions = ref<{ sessionId: string; clientId: string; kind: string; createdAt: string }[]>([]);
const devices = ref<
  { deviceId: string; name: string; platform: string; revoked: boolean }[]
>([]);
const loading = ref(true);
const error = ref<string | null>(null);

// ---- account details: display name ----
const displayNameDraft = ref('');
const savingProfile = ref(false);
const profileMessage = ref<string | null>(null);
const profileError = ref<string | null>(null);

// ---- account details: password ----
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
/** A renewal is possible while a membership is live; a purchase needs headroom. */
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
    artifactCount.value = lib.entitlements?.length ?? 0;
    sessions.value = activeSessions;
    devices.value = activeDevices;
    membership.value = membershipStatus;
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
</script>

<template>
  <div class="space-y-6">
    <PageHeader :title="t('account.title')" />
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else-if="user">
      <!-- Hero — the hive passport. One line says where you stand and what to
           do next: tier crest, level, membership deadline, renew/purchase. -->
      <MagicCard class="hive-hero relative overflow-hidden p-5 sm:p-6">
        <div class="absolute -right-6 -top-10 hidden h-60 w-60 sm:block" aria-hidden="true">
          <HexCluster :color="tierHex" />
        </div>

        <div class="relative flex flex-wrap items-center gap-5">
          <!-- Identity cell: the avatar is a honeycomb hexagon, brand-filled. -->
          <div class="hive-avatar shrink-0" aria-hidden="true">
            <span class="hive-avatar__initial">{{ userInitial }}</span>
          </div>

          <div class="min-w-0 flex-1">
            <h2 class="flex flex-wrap items-center gap-2 text-xl font-bold">
              {{ user.displayName }}
              <Badge v-for="role in user.roles" :key="role" tone="muted">
                {{ t(`role.${role}`) }}
              </Badge>
            </h2>
            <p class="mt-0.5 text-sm text-muted">{{ user.email }}</p>

            <!-- The one level line: crest, tier name, level, membership
                 deadline (or the next rung), then its action. -->
            <div
              class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2"
              data-testid="account-level-line"
            >
              <span class="shrink-0" :class="tierTextClass">
                <BeeCrest :level="effectiveLevel" :size="30" />
              </span>
              <p class="flex items-baseline gap-1.5 text-[1.35rem] font-bold leading-none tracking-tight">
                <span>{{ t(`beeLevel.${effectiveLevel}`) }}</span>
                <span class="tabular-nums" :class="tierTextClass">Lv{{ effectiveLevel }}</span>
              </p>
              <span
                v-if="hasMembership"
                class="inline-flex items-center gap-2 text-sm text-muted"
                data-testid="account-membership-active"
              >
                <span class="hive-dot" :style="{ background: tierHex }" aria-hidden="true" />
                {{ t('account.memberUntil', { date: formatDate(membership?.membershipExpiresAt ?? '') }) }}
              </span>
              <span v-else-if="nextLevel !== null" class="text-sm text-muted">
                {{ t('account.levelNext', { next: t(`beeLevel.${nextLevel}`) }) }}
              </span>
              <span v-else class="text-sm text-muted">{{ t('account.levelTop') }}</span>
              <RouterLink
                v-if="canUpgrade"
                to="/membership"
                class="btn btn-primary shrink-0 hive-cta"
                data-testid="account-membership-cta"
              >
                {{ hasMembership ? t('membership.renew') : t('account.membershipCta') }}
              </RouterLink>
            </div>

            <!-- Holdings at a glance: artifacts owned, signed-in devices. -->
            <div class="mt-2.5 flex items-center gap-4 text-sm text-muted">
              <span data-testid="account-artifact-count">
                {{ t('account.artifactsCount') }}
                <span class="font-semibold tabular-nums text-ink dark:text-slate-100">
                  {{ artifactCount }}
                </span>
              </span>
              <span data-testid="account-device-count">
                {{ t('account.devices') }}
                <span class="font-semibold tabular-nums text-ink dark:text-slate-100">
                  {{ devices.length }}
                </span>
              </span>
            </div>
          </div>
        </div>
      </MagicCard>

      <!-- Account management: credentials and sign-in footprint. -->
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
  width: 64px;
  height: 72px;
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
  background: var(--hero-gradient);
}
.hive-avatar__initial {
  font-size: 1.6rem;
  font-weight: 700;
  color: #fff;
  line-height: 1;
  /* Optically center within the hexagon's usable area. */
  transform: translateY(2px);
}

/* Wax-seal dot for the active membership deadline. */
.hive-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex: none;
}

/* The level line's action is a slim capsule in the role-badge's proportions
   (long, low-crowned) — it must out-rank .btn's unlayered styles, hence a
   scoped rule instead of utilities. */
.hive-cta {
  min-height: 0;
  padding: 0.3rem 1.4rem;
  font-size: 0.8125rem;
  border-radius: 999px;
}
</style>
