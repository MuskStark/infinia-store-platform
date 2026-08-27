<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type AuditEvent, type Report } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';

/**
 * Platform admin console (design §12.4 管理): abuse report queue, security
 * withdrawals (yank / quarantine by release id) and the global audit trail.
 * Review decisions stay in the reviewer queue.
 */
const { t } = useI18n();

const tab = ref<'reports' | 'withdraw' | 'audit'>('reports');
const reports = ref<Report[]>([]);
const auditEvents = ref<AuditEvent[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const notes = ref<Record<string, string>>({});

const releaseId = ref('');
const reason = ref('');

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [r, a] = await Promise.all([
      api.get<Report[]>('/api/v1/admin/reports?status=OPEN'),
      api.get<AuditEvent[]>('/api/v1/admin/audit-events?limit=100'),
    ]);
    reports.value = r;
    auditEvents.value = a;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function resolve(report: Report, resolution: 'ACTIONED' | 'DISMISSED') {
  await api.post(`/api/v1/admin/reports/${report.reportId}/resolution`, {
    resolution,
    note: notes.value[report.reportId ?? ''] ?? '',
  });
  await load();
}

async function withdraw(kind: 'yank' | 'quarantine') {
  if (!releaseId.value) return;
  await api.post(`/api/v1/admin/releases/${releaseId.value}/${kind}`, { reason: reason.value });
  reason.value = '';
}
</script>

<template>
  <div class="space-y-8">
    <h1 class="text-2xl font-bold">{{ t('admin.title') }}</h1>
    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingGrid v-else-if="loading" />

    <template v-else>
      <nav class="flex flex-wrap gap-1 border-b border-line dark:border-slate-800" role="tablist">
        <button
          v-for="key in ['reports', 'withdraw', 'audit'] as const"
          :key="key"
          role="tab"
          :aria-selected="tab === key"
          class="rounded-t-xl px-4 py-2 text-sm"
          :class="tab === key ? 'border-b-2 border-accent font-semibold' : 'text-muted'"
          @click="tab = key"
        >
          {{ t(`admin.${key}`) }}
        </button>
      </nav>

      <section v-if="tab === 'reports'" class="space-y-4">
        <EmptyState v-if="!reports.length" :title="t('admin.noReports')" />
        <MagicCard v-for="report in reports" :key="report.reportId" class="p-6">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 class="font-semibold">{{ report.listingName }}</h2>
              <code class="text-xs text-muted">{{ report.listingCoordinate }}</code>
            </div>
            <div class="flex items-center gap-2">
              <Badge tone="danger">{{ t(`admin.reason.${report.reason}`) }}</Badge>
              <Badge tone="muted">{{ report.status }}</Badge>
            </div>
          </div>
          <p v-if="report.details" class="mt-3 text-sm">{{ report.details }}</p>
          <p class="mt-2 text-xs text-muted">{{ t('admin.reportedAt') }}: {{ report.createdAt }}</p>
          <div class="mt-4 flex flex-col gap-2 sm:flex-row">
            <textarea
              v-model="notes[report.reportId ?? '']"
              :placeholder="t('admin.resolutionNote')"
              rows="2"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <div class="flex gap-2">
              <button
                class="rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white"
                @click="resolve(report, 'ACTIONED')"
              >
                {{ t('admin.action') }}
              </button>
              <button
                class="rounded-xl border border-line px-4 py-2 text-sm dark:border-slate-800"
                @click="resolve(report, 'DISMISSED')"
              >
                {{ t('admin.dismiss') }}
              </button>
            </div>
          </div>
        </MagicCard>
      </section>

      <section v-if="tab === 'withdraw'" class="space-y-4">
        <p class="text-sm text-muted">{{ t('admin.withdrawHint') }}</p>
        <MagicCard class="p-6">
          <form class="flex flex-col gap-2 sm:flex-row" @submit.prevent="withdraw('yank')">
            <input
              v-model="releaseId"
              required
              placeholder="release UUID"
              class="w-full rounded-xl border border-line px-3 py-2 font-mono text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <input
              v-model="reason"
              :placeholder="t('admin.reasonLabel')"
              class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
            />
            <button class="rounded-xl border border-line px-4 py-2 text-sm font-semibold dark:border-slate-800">
              {{ t('admin.yank') }}
            </button>
          </form>
          <button
            class="mt-3 w-full rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white"
            :disabled="!releaseId"
            @click="withdraw('quarantine')"
          >
            {{ t('admin.quarantine') }}
          </button>
          <p class="mt-2 text-xs text-muted">{{ t('admin.quarantineHint') }}</p>
        </MagicCard>
      </section>

      <section v-if="tab === 'audit'">
        <EmptyState v-if="!auditEvents.length" :title="t('common.empty')" />
        <ul v-else class="space-y-1 text-xs">
          <li
            v-for="event in auditEvents"
            :key="event.eventId"
            class="rounded-lg border border-line px-3 py-2 dark:border-slate-800"
          >
            <span class="text-muted">{{ event.occurredAt }}</span>
            · <span class="font-semibold">{{ event.action }}</span>
            · {{ event.resourceType }}/<code>{{ event.resourceId }}</code>
            · <span class="text-muted">{{ event.actorType }}:{{ event.actorId }}</span>
            <span v-if="event.afterSummary"> → {{ event.afterSummary }}</span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
