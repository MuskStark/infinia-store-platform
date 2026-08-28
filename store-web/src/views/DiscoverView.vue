<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { api, type CatalogItem, type CatalogPage } from '../api/client';
import {
  AnimatedGridPattern,
  BlurFade,
  MagicCard,
  Marquee,
  NumberTicker,
} from '@infinia/magic-ui-vue';
import ListingCard from '../components/ListingCard.vue';
import LoadingGrid from '../components/LoadingGrid.vue';
import ErrorState from '../components/ErrorState.vue';

const { t } = useI18n();
const items = ref<CatalogItem[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const page = await api.get<CatalogPage>('/api/v1/catalog?limit=24&sort=downloads');
    items.value = page.items ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'error';
  } finally {
    loading.value = false;
  }
}
onMounted(load);

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
    <!-- Hero: low-contrast background motion, content first (design §12.5). -->
    <section class="relative overflow-hidden rounded-3xl border border-line bg-surface p-10 dark:border-slate-800 dark:bg-slate-900">
      <div class="pointer-events-none absolute inset-0 opacity-40">
        <AnimatedGridPattern :cell-size="44" :width="32" :height="14" />
      </div>
      <div class="relative max-w-2xl">
        <BlurFade>
          <h1 class="text-4xl font-extrabold tracking-tight">
            {{ t('discover.heroTitle') }}
          </h1>
          <p class="mt-3 text-lg text-muted dark:text-slate-400">
            {{ t('discover.heroSubtitle') }}
          </p>
        </BlurFade>
        <div class="mt-6 flex gap-8">
          <div>
            <div class="text-2xl font-bold">
              <NumberTicker :value="items.length" />
            </div>
            <div class="text-xs text-muted dark:text-slate-400">{{ t('discover.statsListings') }}</div>
          </div>
          <div>
            <div class="text-2xl font-bold">
              <NumberTicker :value="totalDownloads" />
            </div>
            <div class="text-xs text-muted dark:text-slate-400">{{ t('discover.statsDownloads') }}</div>
          </div>
        </div>
      </div>
    </section>

    <section aria-labelledby="types-heading">
      <h2 id="types-heading" class="mb-4 text-lg font-semibold">{{ t('discover.categories') }}</h2>
      <Marquee :duration="26" class="rounded-2xl">
        <RouterLink
          v-for="type in types"
          :key="type"
          :to="{ name: 'browse', query: { type } }"
          class="rounded-2xl border border-line bg-surface px-6 py-4 font-medium hover:border-accent dark:border-slate-800 dark:bg-slate-900"
        >
          {{ t(`type.${type}`) }}
        </RouterLink>
      </Marquee>
    </section>

    <section aria-labelledby="featured-heading">
      <h2 id="featured-heading" class="mb-4 text-lg font-semibold">{{ t('discover.featured') }}</h2>
      <ErrorState v-if="error" :message="error" @retry="load" />
      <LoadingGrid v-else-if="loading" />
      <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <ListingCard v-for="item in featured" :key="item.coordinate" :item="item" featured />
      </div>
    </section>

    <section aria-labelledby="latest-heading">
      <div class="mb-4 flex items-center justify-between">
        <h2 id="latest-heading" class="text-lg font-semibold">{{ t('discover.latest') }}</h2>
        <RouterLink class="text-sm text-accent" :to="{ name: 'browse' }">
          {{ t('common.viewAll') }} →
        </RouterLink>
      </div>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <MagicCard v-for="item in latest" :key="item.coordinate" class="p-5">
          <RouterLink :to="`/listing/${item.namespace}/${item.slug}`">
            <h3 class="font-semibold">{{ item.name }}</h3>
            <p class="mt-1 line-clamp-2 text-sm text-muted dark:text-slate-400">
              {{ item.summary }}
            </p>
          </RouterLink>
        </MagicCard>
      </div>
    </section>
  </div>
</template>
