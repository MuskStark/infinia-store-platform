<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type PublicUser } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import { useAuthStore } from '../stores/auth';
import EmptyState from '../components/EmptyState.vue';

const { t } = useI18n();
const auth = useAuthStore();
const user = ref<PublicUser | null>(null);
const sessions = ref<{ sessionId: string; clientId: string; kind: string; createdAt: string }[]>([]);
const devices = ref<
  { deviceId: string; name: string; platform: string; revoked: boolean }[]
>([]);

const currentPassword = ref('');
const newPassword = ref('');
const passwordMessage = ref<string | null>(null);
const passwordError = ref<string | null>(null);

async function load() {
  user.value = await api.get<PublicUser>('/api/v1/me');
  sessions.value = await api.get('/api/v1/me/sessions');
  devices.value = await api.get('/api/v1/me/devices');
}
onMounted(load);

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

function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
}
</script>

<template>
  <div class="space-y-8" v-if="user">
    <h1 class="text-2xl font-bold">{{ t('account.title') }}</h1>

    <MagicCard class="p-6">
      <h2 class="mb-3 font-semibold">{{ t('account.profile') }}</h2>
      <dl class="grid gap-2 text-sm sm:grid-cols-2">
        <div><dt class="text-muted">{{ t('account.displayName') }}</dt><dd class="font-medium">{{ user.displayName }}</dd></div>
        <div><dt class="text-muted">{{ t('account.email') }}</dt><dd class="font-medium">{{ user.email }}</dd></div>
        <div class="sm:col-span-2">
          <dt class="text-muted">{{ t('account.roles') }}</dt>
          <dd class="mt-1 flex flex-wrap gap-1">
            <Badge v-for="role in user.roles" :key="role" tone="accent">{{ t(`role.${role}`) }}</Badge>
          </dd>
        </div>
      </dl>
    </MagicCard>

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
    </MagicCard>

    <section>
      <h2 class="mb-3 font-semibold">{{ t('account.sessions') }}</h2>
      <EmptyState v-if="!sessions.length" :title="t('account.noSessions')" />
      <ul v-else class="space-y-2 text-sm">
        <li
          v-for="session in sessions"
          :key="session.sessionId"
          class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line p-3 dark:border-slate-800"
        >
          <div>
            <div class="font-medium">{{ session.clientId }}</div>
            <div class="text-xs text-muted">{{ session.kind }} · {{ formatDateTime(session.createdAt) }}</div>
          </div>
          <button class="rounded-lg border border-red-300 px-3 py-2 text-xs text-red-600 dark:border-red-900 dark:text-red-400" @click="revokeSession(session.sessionId)">
            {{ t('account.revoke') }}
          </button>
        </li>
      </ul>
    </section>

    <section>
      <h2 class="mb-3 font-semibold">{{ t('account.devices') }}</h2>
      <EmptyState v-if="!devices.length" :title="t('account.noDevices')" />
      <ul v-else class="space-y-2 text-sm">
        <li
          v-for="device in devices"
          :key="device.deviceId"
          class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line p-3 dark:border-slate-800"
        >
          <div>
            {{ device.name }} · {{ device.platform }}
            <Badge v-if="device.revoked" tone="danger">{{ t('account.revoked') }}</Badge>
          </div>
          <button
            v-if="!device.revoked"
            class="rounded-lg border border-red-300 px-3 py-2 text-xs text-red-600 dark:border-red-900 dark:text-red-400"
            @click="revokeDevice(device.deviceId)"
          >
            {{ t('account.revoke') }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
