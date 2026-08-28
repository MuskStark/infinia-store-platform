<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useCatalogStore, type ListingTypeFilter, type SortKey } from '../stores/catalog';
import ListingCard from '../components/ListingCard.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';
import EmptyState from '../components/EmptyState.vue';

const { t } = useI18n();
const route = useRoute();
const catalog = useCatalogStore();

const types: (ListingTypeFilter)[] = [null, 'APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW'];
const sorts: SortKey[] = ['relevance', 'recent', 'downloads', 'favorites'];

onMounted(() => {
  catalog.type = ((route.query.type as ListingTypeFilter) ?? null);
  catalog.query = (route.query.q as string) ?? '';
  catalog.browse();
});

watch(
  () => [catalog.type, catalog.sort],
  () => catalog.browse(),
);

function submitSearch() {
  catalog.browse();
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-center gap-3">
      <form class="flex flex-1 gap-2" @submit.prevent="submitSearch">
        <input
          v-model="catalog.query"
          type="search"
          :placeholder="t('common.search')"
          class="w-full max-w-md rounded-xl border border-line bg-surface px-4 py-2 dark:border-slate-800 dark:bg-slate-900"
        />
        <button class="rounded-xl bg-accent px-4 py-2 font-medium text-white" type="submit">
          {{ t('common.searchAction') }}
        </button>
      </form>
      <select
        v-model="catalog.sort"
        class="rounded-xl border border-line bg-surface px-3 py-2 dark:border-slate-800 dark:bg-slate-900"
        aria-label="sort"
      >
        <option v-for="sort in sorts" :key="sort" :value="sort">{{ t(`sort.${sort}`) }}</option>
      </select>
    </div>

    <div class="flex flex-wrap gap-2" role="tablist" aria-label="type">
      <button
        v-for="type in types"
        :key="type ?? 'all'"
        class="rounded-xl border px-4 py-2 text-sm"
        :class="
          catalog.type === type
            ? 'border-accent bg-accent/10 text-accent'
            : 'border-line bg-surface dark:border-slate-800 dark:bg-slate-900'
        "
        role="tab"
        :aria-selected="catalog.type === type"
        @click="catalog.type = type"
      >
        {{ type ? t(`type.${type}`) : t('common.viewAll') }}
      </button>
    </div>

    <ErrorState v-if="catalog.error" :message="catalog.error" @retry="catalog.browse()" />
    <LoadingGrid v-else-if="catalog.loading && !catalog.items.length" />
    <EmptyState v-else-if="!catalog.items.length" :title="t('common.empty')" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <ListingCard v-for="item in catalog.items" :key="item.coordinate" :item="item" />
    </div>

    <div v-if="catalog.nextCursor" class="text-center">
      <button
        class="rounded-xl border border-line px-6 py-2 dark:border-slate-800"
        @click="catalog.browse(false)"
      >
        {{ t('common.viewAll') }}
      </button>
    </div>
  </div>
</template>
