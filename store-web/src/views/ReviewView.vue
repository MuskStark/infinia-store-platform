<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type Review } from '../api/client';
import { Badge, MagicCard } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import StateChip from '../components/StateChip.vue';

/** Review queue for REVIEWER/PLATFORM_ADMIN roles (design §7.3, §8). */
const { t } = useI18n();
const reviews = ref<Review[]>([]);
const notes = ref<Record<string, string>>({});
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    reviews.value = await api.get<Review[]>('/api/v1/reviews?status=IN_REVIEW');
  } finally {
    loading.value = false;
  }
}
onMounted(load);

async function decide(review: Review, decision: 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES') {
  const reviewId = review.reviewId;
  if (!reviewId) return;
  await api.post(`/api/v1/reviews/${reviewId}/decisions`, {
    decision,
    notes: notes.value[reviewId] ?? '',
  });
  await load();
}
</script>

<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold">{{ t('review.title') }}</h1>
    <EmptyState v-if="!loading && !reviews.length" :title="t('review.queueEmpty')" />
    <MagicCard v-for="review in reviews" :key="review.reviewId" class="p-6">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 class="font-semibold">{{ review.listingName }} · v{{ review.version }}</h2>
          <code class="text-xs text-muted">{{ review.listingCoordinate }}</code>
        </div>
        <StateChip :status="review.status" />
      </div>

      <div v-if="review.findings?.length" class="mt-4">
        <h3 class="mb-2 text-sm font-semibold">{{ t('review.findings') }}</h3>
        <ul class="space-y-1 text-sm">
          <li v-for="finding in review.findings" :key="finding.rule" class="rounded-lg border border-line p-2 dark:border-slate-800">
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
          class="w-full rounded-xl border border-line px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900"
          rows="2"
        />
        <div class="flex gap-2">
          <button class="rounded-xl bg-green-600 px-4 py-2 text-sm font-semibold text-white" @click="decide(review, 'APPROVE')">
            {{ t('review.approve') }}
          </button>
          <button class="rounded-xl border border-line px-4 py-2 text-sm dark:border-slate-800" @click="decide(review, 'REQUEST_CHANGES')">
            {{ t('review.requestChanges') }}
          </button>
          <button class="rounded-xl border border-red-300 px-4 py-2 text-sm text-red-600 dark:border-red-900 dark:text-red-400" @click="decide(review, 'REJECT')">
            {{ t('review.reject') }}
          </button>
        </div>
      </div>
    </MagicCard>
  </div>
</template>
