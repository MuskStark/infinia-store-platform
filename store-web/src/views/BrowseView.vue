<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useCatalogStore, type ListingTypeFilter, type SortKey } from '../stores/catalog';
import ListingCard from '../components/ListingCard.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';
import EmptyState from '../components/EmptyState.vue';
import SelectMenu from '../components/SelectMenu.vue';

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
    <!-- Composite search bar + filter row, marketplace search page style. -->
    <form class="flex" role="search" @submit.prevent="submitSearch">
      <label class="sr-only" for="browse-search">{{ t('common.search') }}</label>
      <input
        id="browse-search"
        v-model="catalog.query"
        type="search"
        :placeholder="t('common.search')"
        class="h-11 w-full max-w-2xl rounded-l-lg border border-line bg-surface px-4 text-[15px] text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
      />
      <button
        type="submit"
        class="shrink-0 rounded-r-lg bg-accent px-5 text-sm font-medium text-white transition-colors hover:brightness-110"
      >
        {{ t('common.searchAction') }}
      </button>
    </form>

    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex flex-wrap gap-2" role="tablist" aria-label="type">
        <button
          v-for="type in types"
          :key="type ?? 'all'"
          class="rounded-lg border px-3.5 py-1.5 text-sm font-medium transition-colors"
          :class="
            catalog.type === type
              ? 'border-accent bg-accent/5 font-semibold text-accent'
              : 'border-line bg-surface text-muted hover:border-muted/40 hover:text-ink dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400 dark:hover:text-slate-200'
          "
          role="tab"
          :aria-selected="catalog.type === type"
          @click="catalog.type = type"
        >
          {{ type ? t(`type.${type}`) : t('common.viewAll') }}
        </button>
      </div>
      <SelectMenu
        :model-value="catalog.sort"
        class="w-44"
        :options="sorts.map((sort) => ({ value: sort, label: `${t('common.sort')}: ${t(`sort.${sort}`)}` }))"
        :aria-label="t('common.sort')"
        @update:model-value="catalog.sort = $event as SortKey"
      />
    </div>

    <ErrorState v-if="catalog.error" :message="catalog.error" @retry="catalog.browse()" />
    <LoadingGrid v-else-if="catalog.loading && !catalog.items.length" />
    <EmptyState v-else-if="!catalog.items.length" :title="t('common.empty')" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <ListingCard v-for="item in catalog.items" :key="item.coordinate" :item="item" />
    </div>

    <div v-if="catalog.nextCursor" class="text-center">
      <button class="btn btn-secondary px-8" @click="catalog.browse(false)">
        {{ t('common.viewAll') }}
      </button>
    </div>
  </div>
</template>
