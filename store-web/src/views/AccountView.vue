<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type Library, type PublicUser } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import BeeLevelBadge from '../components/BeeLevelBadge.vue';
import { BEE_LEVELS, beeMark } from '../bee-levels';
import EmptyState from '../components/EmptyState.vue';
import ErrorState from '../components/ErrorState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import { formatDate, formatDateTime } from '../utils/format';
import { useAuthStore } from '../stores/auth';

/**
 * User Center (用户中心): one signed-in landing page aggregating identity,
 * Infinia Level ladder, profile editing, library/organization summaries,
 * role-aware quick links and account security (password, sessions, devices).
 */
const { t } = useI18n();
const auth = useAuthStore();

const user = ref<PublicUser | null>(null);
const library = ref<Library | null>(null);
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
const nextLevel = computed(() => (beeLevel.value < 4 ? beeLevel.value + 1 : null));

const quickLinks = computed(() => {
  const links: { to: string; label: string }[] = [
    { to: '/library', label: t('nav.library') },
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
    const [me, lib, activeSessions, activeDevices] = await Promise.all([
      api.get<PublicUser>('/api/v1/me'),
      api.get<Library>('/api/v1/me/library'),
      api.get<{ sessionId: string; clientId: string; kind: string; createdAt: string }[]>(
        '/api/v1/me/sessions',
      ),
      api.get<{ deviceId: string; name: string; platform: string; revoked: boolean }[]>(
        '/api/v1/me/devices',
      ),
    ]);
    user.value = me;
    displayNameDraft.value = me.displayName;
    library.value = lib;
    sessions.value = activeSessions;
    devices.value = activeDevices;
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
  <div class="space-y-8">
    <h1 class="text-2xl font-bold">{{ t('account.title') }}</h1>
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else-if="user">
      <!-- Overview: identity, Infinia Level ladder, roles, quick links -->
      <MagicCard class="p-6">
        <div class="flex flex-wrap items-start gap-6">
          <div
            class="grid h-16 w-16 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-accent to-accent2 text-2xl font-bold text-white"
          >
            {{ (user.displayName ?? user.email).charAt(0).toUpperCase() }}
          </div>
          <div class="min-w-0 flex-1">
            <h2 class="text-xl font-bold">{{ user.displayName }}</h2>
            <p class="text-sm text-muted">{{ user.email }}</p>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <BeeLevelBadge :level="beeLevel" />
              <span class="text-xs text-muted">
                {{ nextLevel !== null
                  ? t('account.levelNext', { next: t(`beeLevel.${nextLevel}`) })
                  : t('account.levelTop') }}
              </span>
            </div>
            <!-- The ladder: each level keeps its own mark; the current one is highlighted -->
            <ol class="mt-4 flex flex-wrap gap-2">
              <li
                v-for="level in BEE_LEVELS"
                :key="level"
                class="flex items-center gap-2 rounded-xl border px-3 py-1.5 text-xs"
                :class="level === beeLevel
                  ? 'border-accent bg-accent/10 font-semibold'
                  : level < beeLevel
                    ? 'border-line text-muted dark:border-slate-800'
                    : 'border-line opacity-50 dark:border-slate-800'"
                :aria-current="level === beeLevel ? 'step' : undefined"
              >
                <span>{{ beeMark(level).emblem }}</span>
                {{ t(`beeLevel.${level}`) }}
                <span class="text-muted">Lv{{ level }}</span>
              </li>
            </ol>
          </div>
          <div class="flex w-full flex-col gap-2 sm:w-48">
            <dt class="text-xs text-muted">{{ t('account.roles') }}</dt>
            <dd class="flex flex-wrap gap-1">
              <Badge v-for="role in user.roles" :key="role" tone="accent">
                {{ t(`role.${role}`) }}
              </Badge>
            </dd>
            <dt class="mt-2 text-xs text-muted">{{ t('account.quickLinks') }}</dt>
            <dd class="flex flex-col gap-1">
              <RouterLink
                v-for="link in quickLinks"
                :key="link.to"
                :to="link.to"
                class="rounded-xl border border-line px-3 py-2 text-center text-sm hover:text-accent dark:border-slate-800"
              >
                {{ link.label }}
              </RouterLink>
            </dd>
          </div>
        </div>
      </MagicCard>

      <!-- Everything below the overview lives in a compact two-column grid. -->
      <div class="grid gap-6 lg:grid-cols-2">
        <!-- Profile editing -->
        <MagicCard class="p-6">
          <h2 class="mb-3 font-semibold">{{ t('account.editProfile') }}</h2>
          <form class="flex flex-col gap-2 sm:flex-row sm:items-end" @submit.prevent="saveProfile">
            <label class="w-full text-sm">
              {{ t('account.displayName') }}
              <input
                v-model="displayNameDraft"
                required
                minlength="1"
                maxlength="64"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <button
              :disabled="savingProfile || displayNameDraft.trim() === user.displayName"
              class="shrink-0 self-end whitespace-nowrap rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
            >
              {{ t('common.confirm') }}
            </button>
          </form>
          <p v-if="profileMessage" class="mt-2 text-sm text-green-600 dark:text-green-400">
            {{ profileMessage }}
          </p>
          <p v-if="profileError" class="mt-2 text-sm text-red-600 dark:text-red-400">
            {{ profileError }}
          </p>
        </MagicCard>

        <!-- Library summary -->
        <MagicCard class="p-6">
          <div class="mb-3 flex items-center justify-between">
            <h2 class="font-semibold">{{ t('account.myLibrary') }}</h2>
            <RouterLink to="/library" class="text-sm text-accent hover:underline">
              {{ t('common.viewAll') }} →
            </RouterLink>
          </div>
          <dl class="grid grid-cols-3 gap-3 text-center">
            <div class="rounded-xl border border-line p-3 dark:border-slate-800">
              <dd class="text-2xl font-bold">{{ library?.favorites?.length ?? 0 }}</dd>
              <dt class="text-xs text-muted">{{ t('account.favoritesCount') }}</dt>
            </div>
            <div class="rounded-xl border border-line p-3 dark:border-slate-800">
              <dd class="text-2xl font-bold">{{ library?.entitlements?.length ?? 0 }}</dd>
              <dt class="text-xs text-muted">{{ t('account.entitlementsCount') }}</dt>
            </div>
            <div class="rounded-xl border border-line p-3 dark:border-slate-800">
              <dd class="text-2xl font-bold">{{ library?.installHistory?.length ?? 0 }}</dd>
              <dt class="text-xs text-muted">{{ t('account.installedCount') }}</dt>
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
        <MagicCard class="p-6">
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

        <!-- Security: password, sessions, devices -->
        <MagicCard class="p-6">
          <h2 class="mb-3 font-semibold">{{ t('account.security') }}</h2>
          <form class="flex flex-col gap-2 sm:flex-row sm:items-end" @submit.prevent="changePassword">
            <label class="w-full text-sm">
              {{ t('account.currentPassword') }}
              <input
                v-model="currentPassword"
                type="password"
                required
                autocomplete="current-password"
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
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
                class="mt-1 w-full rounded-xl border border-line px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
              />
            </label>
            <button
              class="shrink-0 self-end whitespace-nowrap rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white"
            >
              {{ t('account.changePassword') }}
            </button>
          </form>
          <p v-if="passwordMessage" class="mt-2 text-sm text-green-600 dark:text-green-400">{{ passwordMessage }}</p>
          <p v-if="passwordError" class="mt-2 text-sm text-red-600 dark:text-red-400">{{ passwordError }}</p>

          <details class="mt-4">
            <summary class="cursor-pointer text-sm font-medium text-muted hover:text-accent">
              {{ t('account.sessions') }} ({{ sessions.length }})
            </summary>
            <EmptyState v-if="!sessions.length" :title="t('account.noSessions')" />
            <ul v-else class="mt-2 space-y-2 text-sm">
              <li
                v-for="session in sessions"
                :key="session.sessionId"
                class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line px-3 py-2 dark:border-slate-800"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <Badge tone="muted">{{ session.clientId }}</Badge>
                  <Badge tone="muted">{{ session.kind }}</Badge>
                  <span class="text-xs text-muted">{{ formatDateTime(session.createdAt) }}</span>
                </div>
                <button
                  class="rounded-lg border border-line px-2.5 py-1 text-xs font-semibold text-red-600 dark:border-slate-800 dark:text-red-400"
                  @click="revokeSession(session.sessionId)"
                >
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
                class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line px-3 py-2 dark:border-slate-800"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-medium">{{ device.name }}</span>
                  <Badge tone="muted">{{ device.platform }}</Badge>
                  <Badge v-if="device.revoked" tone="danger">{{ t('account.revoked') }}</Badge>
                </div>
                <button
                  v-if="!device.revoked"
                  class="rounded-lg border border-line px-2.5 py-1 text-xs font-semibold text-red-600 dark:border-slate-800 dark:text-red-400"
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
