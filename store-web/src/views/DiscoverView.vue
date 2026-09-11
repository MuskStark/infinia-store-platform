<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { api, type CatalogItem, type CatalogPage } from '../api/client';
import { BlurFade, NumberTicker } from '@infinia/magic-ui-vue';
import ListingCard from '../components/ListingCard.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';
import { formatNumber } from '../utils/format';

const { t } = useI18n();
const router = useRouter();
const items = ref<CatalogItem[]>([]);
const totalListings = ref(0);
const loading = ref(true);
const error = ref<string | null>(null);
const searchQuery = ref('');

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const page = await api.get<CatalogPage>('/api/v1/catalog?limit=24&sort=downloads');
    items.value = page.items ?? [];
    totalListings.value = page.totalEstimate ?? items.value.length;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(load);

function submitSearch() {
  router.push({ name: 'browse', query: searchQuery.value ? { q: searchQuery.value } : {} });
}

// Editorial shelf from platform admins (design §12.4); falls back to the most
// downloaded listings until the admin features something.
const featured = computed(() =>
  items.value.some((i: { featured?: boolean }) => i.featured)
    ? items.value.filter((i: { featured?: boolean }) => i.featured).slice(0, 5)
    : items.value.slice(0, 5));
const latest = computed(() => items.value.slice(5));
const totalDownloads = computed(() =>
  items.value.reduce((sum, item) => sum + (item.downloads ?? 0), 0),
);
const types = ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW'] as const;
</script>

<template>
  <div class="space-y-12">
    <!-- Marketplace hero: light band with JetBrains gradient honeycomb cells
         drifting at the edges; content first, composite search below. -->
    <section class="relative -mx-4 -mt-8 overflow-hidden border-b border-line bg-surface-muted px-4 pb-14 pt-16 dark:border-slate-800 dark:bg-slate-950">
      <div class="hero-shape right-[-72px] top-[-48px] hidden h-72 w-72 opacity-90 lg:block" aria-hidden="true" />
      <div class="hero-shape left-[-96px] bottom-[-120px] hidden h-80 w-80 opacity-80 lg:block" aria-hidden="true" />
      <div class="hero-shape right-[280px] bottom-[-90px] hidden h-40 w-40 opacity-40 md:block" aria-hidden="true" />

      <div class="relative mx-auto max-w-7xl">
        <div class="max-w-2xl">
          <BlurFade>
            <h1 class="text-4xl font-bold leading-tight tracking-tight text-ink dark:text-slate-100 md:text-[2.75rem] md:leading-[1.15]">
              {{ t('discover.heroTitle') }}
            </h1>
            <p class="mt-4 text-lg leading-7 text-muted dark:text-slate-400">
              {{ t('discover.heroSubtitle') }}
            </p>
          </BlurFade>

          <!-- Composite search bar: blue submit segment + input, marketplace style. -->
          <form class="mt-8 flex max-w-2xl" role="search" @submit.prevent="submitSearch">
            <label class="sr-only" for="hero-search">{{ t('common.search') }}</label>
            <button
              type="submit"
              class="shrink-0 rounded-l-lg bg-accent px-5 text-sm font-medium text-white transition-colors hover:brightness-110"
            >
              {{ t('common.searchAction') }}
            </button>
            <input
              id="hero-search"
              v-model="searchQuery"
              type="search"
              :placeholder="t('common.search')"
              class="h-11 w-full rounded-r-lg border border-line border-l-0 bg-surface px-4 text-[15px] text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </form>

          <div class="mt-6 flex gap-8">
            <div>
              <div class="text-2xl font-bold text-ink dark:text-slate-100">
                <NumberTicker :value="totalListings" :format="formatNumber" />
              </div>
              <div class="mt-0.5 text-xs text-muted dark:text-slate-400">{{ t('discover.statsListings') }}</div>
            </div>
            <div>
              <div class="text-2xl font-bold text-ink dark:text-slate-100">
                <NumberTicker :value="totalDownloads" :format="formatNumber" />
              </div>
              <div class="mt-0.5 text-xs text-muted dark:text-slate-400">{{ t('discover.statsDownloads') }}</div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section aria-labelledby="types-heading" class="pt-4">
      <h2 id="types-heading" class="mb-4 text-lg font-bold">{{ t('discover.categories') }}</h2>
      <div class="flex flex-wrap gap-2.5">
        <RouterLink
          v-for="type in types"
          :key="type"
          :to="{ name: 'browse', query: { type } }"
          class="card px-5 py-3 text-sm font-medium transition-colors hover:border-accent hover:text-accent"
        >
          {{ t(`type.${type}`) }}
        </RouterLink>
      </div>
    </section>

    <section aria-labelledby="featured-heading">
      <div class="mb-4 flex items-center justify-between">
        <h2 id="featured-heading" class="text-lg font-bold">{{ t('discover.featured') }}</h2>
        <RouterLink class="text-sm text-accent hover:underline" :to="{ name: 'browse' }">
          {{ t('common.viewAll') }}
        </RouterLink>
      </div>
      <ErrorState v-if="error" :message="error" @retry="load" />
      <LoadingGrid v-else-if="loading" />
      <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <ListingCard v-for="item in featured" :key="item.coordinate" :item="item" featured />
      </div>
    </section>

    <section aria-labelledby="latest-heading">
      <h2 id="latest-heading" class="mb-4 text-lg font-bold">{{ t('discover.latest') }}</h2>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <ListingCard v-for="item in latest" :key="item.coordinate" :item="item" />
      </div>
    </section>
  </div>
</template>
