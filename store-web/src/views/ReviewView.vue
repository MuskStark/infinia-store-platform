<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, ApiRequestError, type Review } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import StateChip from '../components/StateChip.vue';
import PageHeader from '../components/PageHeader.vue';
import { formatDateTime } from '../utils/format';

/**
 * Review queue for REVIEWER/PLATFORM_ADMIN roles (design §7.3, §8). Approving
 * publishes the release immediately, so approval asks for a second click and
 * reject/request-changes require a note the publisher can act on.
 */
const { t } = useI18n();
const reviews = ref<Review[]>([]);
const notes = ref<Record<string, string>>({});
const loading = ref(true);
const actionError = ref<string | null>(null);
/** Review id currently waiting for the approval confirmation click. */
const confirmId = ref<string | null>(null);

async function load() {
  loading.value = true;
  actionError.value = null;
  try {
    reviews.value = await api.get<Review[]>('/api/v1/reviews?status=IN_REVIEW');
  } finally {
    loading.value = false;
  }
}
onMounted(load);

function listingLink(coordinate?: string): string {
  const parts = (coordinate ?? '').replace('infinia://', '').split('/');
  return parts.length >= 3 ? `/listing/${parts[1]}/${parts[2]}` : '/browse';
}

function hasNotes(review: Review): boolean {
  return Boolean(notes.value[review.reviewId ?? '']?.trim());
}

async function decide(review: Review, decision: 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES') {
  const reviewId = review.reviewId;
  if (!reviewId) return;
  // First click arms the button; the second click within the window confirms.
  if (decision === 'APPROVE' && confirmId.value !== reviewId) {
    confirmId.value = reviewId;
    window.setTimeout(() => {
      if (confirmId.value === reviewId) confirmId.value = null;
    }, 4000);
    return;
  }
  confirmId.value = null;
  actionError.value = null;
  try {
    await api.post(`/api/v1/reviews/${reviewId}/decisions`, {
      decision,
      notes: notes.value[reviewId] ?? '',
    });
    await load();
  } catch (e) {
    actionError.value = e instanceof ApiRequestError
      ? (e.detail ?? e.message)
      : e instanceof Error ? e.message : String(e);
  }
}
</script>

<template>
  <div class="space-y-6">
    <PageHeader :title="t('review.title')">
      <template #actions>
        <Badge tone="accent">{{ t('review.queueCount', { n: reviews.length }) }}</Badge>
        <button class="btn btn-secondary btn-sm" :disabled="loading" @click="load">
          {{ t('review.refresh') }}
        </button>
      </template>
    </PageHeader>

    <p v-if="actionError" class="alert alert-error" role="alert">{{ actionError }}</p>

    <EmptyState v-if="!loading && !reviews.length" :title="t('review.queueEmpty')" />
    <MagicCard v-for="review in reviews" :key="review.reviewId" class="p-6">
      <div class="flex flex-wrap items-start justify-between gap-2">
        <div class="min-w-0">
          <h2 class="font-semibold">
            <RouterLink :to="listingLink(review.listingCoordinate)" class="hover:text-accent">
              {{ review.listingName }}
            </RouterLink>
            · v{{ review.version }}
          </h2>
          <code class="text-xs text-muted">{{ review.listingCoordinate }}</code>
        </div>
        <div class="flex items-center gap-3">
          <span v-if="review.submittedAt" class="text-xs text-muted">
            {{ t('review.submitted') }}: {{ formatDateTime(review.submittedAt ?? '') }}
          </span>
          <StateChip :status="review.status" />
        </div>
      </div>

      <div class="mt-4">
        <h3 class="mb-2 text-sm font-semibold">{{ t('review.findings') }}</h3>
        <!-- No findings is information too: the reviewer must see the scan ran
             and passed, not an empty section. -->
        <p v-if="!review.findings?.length" class="alert alert-success text-sm">
          {{ t('review.scanPassed') }}
        </p>
        <ul v-else class="space-y-1 text-sm">
          <li v-for="finding in review.findings" :key="finding.rule" class="card p-2">
            <Badge :tone="['ERROR', 'CRITICAL'].includes(finding.severity ?? '') ? 'danger' : 'muted'">
              {{ finding.severity }}
            </Badge>
            {{ finding.rule }} — {{ finding.message }}
          </li>
        </ul>
      </div>

      <div class="mt-4 flex flex-col gap-2 sm:flex-row">
        <textarea
          v-model="notes[review.reviewId ?? '']"
          :placeholder="t('review.notesPlaceholder')"
          class="input"
          rows="2"
        />
        <div class="flex shrink-0 flex-col gap-2">
          <div class="flex gap-2">
            <button
              class="btn btn-success"
              :class="confirmId === review.reviewId ? 'animate-pulse' : ''"
              @click="decide(review, 'APPROVE')"
            >
              {{ confirmId === review.reviewId ? t('review.approveConfirm') : t('review.approve') }}
            </button>
            <button
              class="btn btn-secondary"
              :disabled="!hasNotes(review)"
              :title="hasNotes(review) ? undefined : t('review.notesRequiredHint')"
              @click="decide(review, 'REQUEST_CHANGES')"
            >
              {{ t('review.requestChanges') }}
            </button>
          </div>
          <button
            class="btn btn-danger-outline"
            :disabled="!hasNotes(review)"
            :title="hasNotes(review) ? undefined : t('review.notesRequiredHint')"
            @click="decide(review, 'REJECT')"
          >
            {{ t('review.reject') }}
          </button>
        </div>
      </div>
      <p v-if="!hasNotes(review)" class="mt-2 text-xs text-muted dark:text-slate-400">
        {{ t('review.notesRequiredHint') }}
      </p>
    </MagicCard>
  </div>
</template>
