<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type AuditEvent, type Organization, type OrganizationMember, type Webhook } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';

/**
 * Organization center (design §7.1, §7.3, §12.4 账号: 组织): member RBAC,
 * webhooks and the organization's audit trail. Creating an organization
 * reserves its namespace and is offered here for convenience.
 */
const { t } = useI18n();

const orgs = ref<Organization[]>([]);
const selected = ref<Organization | null>(null);
const members = ref<OrganizationMember[]>([]);
const webhooks = ref<Webhook[]>([]);
const auditEvents = ref<AuditEvent[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const busy = ref(false);
const message = ref<string | null>(null);

const newOrgSlug = ref('');
const newOrgName = ref('');
const memberEmail = ref('');
const memberRole = ref('PUBLISHER');
const webhookUrl = ref('');

async function load() {
  loading.value = true;
  error.value = null;
  try {
    orgs.value = await api.get<Organization[]>('/api/v1/organizations');
    if (!selected.value && orgs.value.length) {
      await select(orgs.value[0]);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function select(org: Organization) {
  selected.value = org;
  message.value = null;
  const [m, w, a] = await Promise.all([
    api.get<OrganizationMember[]>(`/api/v1/organizations/${org.organizationId}/members`),
    api.get<Webhook[]>(`/api/v1/organizations/${org.organizationId}/webhooks`),
    api.get<AuditEvent[]>(`/api/v1/organizations/${org.organizationId}/audit-events`),
  ]);
  members.value = m;
  webhooks.value = w;
  auditEvents.value = a;
}

async function createOrg() {
  busy.value = true;
  try {
    const org = await api.post<Organization>('/api/v1/organizations', {
      slug: newOrgSlug.value,
      name: newOrgName.value || newOrgSlug.value,
    });
    newOrgSlug.value = '';
    newOrgName.value = '';
    orgs.value = await api.get<Organization[]>('/api/v1/organizations');
    await select(org);
  } finally {
    busy.value = false;
  }
}

async function addMember() {
  if (!selected.value || !memberEmail.value) return;
  busy.value = true;
  try {
    await api.post(`/api/v1/organizations/${selected.value.organizationId}/members`, {
      email: memberEmail.value,
      role: memberRole.value,
    });
    memberEmail.value = '';
    await select(selected.value);
  } finally {
    busy.value = false;
  }
}

async function changeRole(member: OrganizationMember, role: string) {
  if (!selected.value) return;
  await api.put(`/api/v1/organizations/${selected.value.organizationId}/members/${member.userId}/role`, { role });
  await select(selected.value);
}

async function removeMember(member: OrganizationMember) {
  if (!selected.value) return;
  await api.delete(`/api/v1/organizations/${selected.value.organizationId}/members/${member.userId}`);
  await select(selected.value);
}

async function createWebhook() {
  if (!selected.value || !webhookUrl.value) return;
  busy.value = true;
  try {
    await api.post(`/api/v1/organizations/${selected.value.organizationId}/webhooks`, {
      url: webhookUrl.value,
      events: ['release.published', 'release.yanked', 'release.quarantined'],
    });
    webhookUrl.value = '';
    await select(selected.value);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="space-y-8">
    <h1 class="text-2xl font-bold">{{ t('org.title') }}</h1>
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />
    <template v-else>
      <MagicCard class="p-6">
        <h2 class="mb-3 font-semibold">{{ t('org.createTitle') }}</h2>
        <form class="flex flex-col gap-2 sm:flex-row" @submit.prevent="createOrg">
          <input
            v-model="newOrgSlug"
            required
            pattern="[a-z0-9][a-z0-9-]{0,62}"
            :placeholder="t('publisher.orgSlug')"
            class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
          />
          <input
            v-model="newOrgName"
            :placeholder="t('publisher.orgName')"
            class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
          />
          <button
            :disabled="busy"
            class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white"
          >
            {{ t('common.confirm') }}
          </button>
        </form>
        <p class="mt-2 text-xs text-muted">{{ t('org.createHint') }}</p>
      </MagicCard>

      <EmptyState v-if="!orgs.length" :title="t('org.none')" />

      <div v-else class="flex flex-wrap gap-2">
        <button
          v-for="org in orgs"
          :key="org.organizationId"
          class="rounded-xl border px-4 py-2 text-sm"
          :class="selected?.organizationId === org.organizationId
            ? 'border-accent font-semibold'
            : 'border-line dark:border-slate-800'"
          @click="select(org)"
        >
          {{ org.name }}
        </button>
      </div>

      <template v-if="selected">
        <section aria-labelledby="members-heading">
          <h2 id="members-heading" class="mb-3 text-lg font-semibold">{{ t('org.members') }}</h2>
          <ul class="space-y-2 text-sm">
            <li
              v-for="member in members"
              :key="member.userId"
              class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line p-3 dark:border-slate-800"
            >
              <div>
                <div class="font-medium">{{ member.displayName ?? member.email }}</div>
                <div class="text-xs text-muted">{{ member.email }}</div>
              </div>
              <div class="flex items-center gap-2">
                <Badge v-if="member.owner" tone="accent">{{ t('org.owner') }}</Badge>
                <select
                  :value="member.role"
                  :disabled="member.owner"
                  class="rounded-lg border border-line bg-surface px-2 py-1 text-xs dark:border-slate-800 dark:bg-slate-900"
                  :aria-label="t('org.role')"
                  @change="changeRole(member, ($event.target as HTMLSelectElement).value)"
                >
                  <option v-for="role in ['PUBLISHER', 'ORG_ADMIN']" :key="role" :value="role">{{ role }}</option>
                </select>
                <button
                  v-if="!member.owner"
                  class="rounded-lg border border-red-300 px-3 py-1 text-xs text-red-600 dark:border-red-900 dark:text-red-400"
                  @click="removeMember(member)"
                >
                  {{ t('org.removeMember') }}
                </button>
              </div>
            </li>
          </ul>
          <form class="mt-3 flex flex-col gap-2 sm:flex-row" @submit.prevent="addMember">
            <input
              v-model="memberEmail"
              type="email"
              required
              :placeholder="t('org.memberEmail')"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <select
              v-model="memberRole"
              class="rounded-xl border border-line bg-surface px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
              :aria-label="t('org.role')"
            >
              <option value="PUBLISHER">PUBLISHER</option>
              <option value="ORG_ADMIN">ORG_ADMIN</option>
            </select>
            <button :disabled="busy" class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white">
              {{ t('org.addMember') }}
            </button>
          </form>
        </section>

        <section aria-labelledby="webhooks-heading">
          <h2 id="webhooks-heading" class="mb-3 text-lg font-semibold">{{ t('org.webhooks') }}</h2>
          <EmptyState v-if="!webhooks.length" :title="t('org.noWebhooks')" />
          <ul v-else class="space-y-2 text-sm">
            <li
              v-for="webhook in webhooks"
              :key="webhook.webhookId"
              class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line p-3 dark:border-slate-800"
            >
              <code class="text-xs">{{ webhook.url }}</code>
              <div class="flex flex-wrap gap-1">
                <Badge v-for="event in webhook.events" :key="event" tone="muted">{{ event }}</Badge>
              </div>
            </li>
          </ul>
          <form class="mt-3 flex flex-col gap-2 sm:flex-row" @submit.prevent="createWebhook">
            <input
              v-model="webhookUrl"
              type="url"
              required
              placeholder="https://ci.example.com/hooks/infinia"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <button :disabled="busy" class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-white">
              {{ t('org.addWebhook') }}
            </button>
          </form>
          <p class="mt-2 text-xs text-muted">{{ t('org.webhookHint') }}</p>
        </section>

        <section aria-labelledby="org-audit-heading">
          <h2 id="org-audit-heading" class="mb-3 text-lg font-semibold">{{ t('org.audit') }}</h2>
          <EmptyState v-if="!auditEvents.length" :title="t('common.empty')" />
          <ul v-else class="space-y-1 text-xs">
            <li
              v-for="event in auditEvents"
              :key="event.eventId"
              class="rounded-lg border border-line px-3 py-2 font-mono dark:border-slate-800"
            >
              {{ event.occurredAt }} · {{ event.action }} · {{ event.actorId }}
            </li>
          </ul>
        </section>
      </template>
    </template>
  </div>
</template>
