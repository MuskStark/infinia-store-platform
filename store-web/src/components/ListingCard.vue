<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Badge } from '@infinia/magic-ui-vue';
import BeeLevelBadge from './BeeLevelBadge.vue';
import { formatNumber } from '../utils/format';
import type { CatalogItem } from '../api/client';

const props = defineProps<{ item: CatalogItem; featured?: boolean }>();
const { t } = useI18n();

const href = computed(
  () => `/listing/${props.item.namespace}/${props.item.slug}`,
);
const typeLabel = computed(() => t(`type.${props.item.type}`));
</script>

<template>
  <!-- Marketplace listing card: icon left, name + publisher right of it, summary,
       then a meta footer row (downloads · version · type). Hairline border, small
       radius, hover lifts the border to ink — no heavy shadows. -->
  <RouterLink
    :to="href"
    class="card group flex h-full flex-col gap-3 p-4 transition-colors hover:border-ink/40 dark:hover:border-slate-500"
  >
    <div
      v-if="featured"
      class="-mt-4 -mx-4 mb-0 h-1 rounded-t-lg"
      style="background: var(--hero-gradient)"
      aria-hidden="true"
    />
    <div class="flex items-start gap-3">
      <img
        v-if="item.iconUrl"
        :src="item.iconUrl"
        alt=""
        class="h-11 w-11 shrink-0 rounded-lg object-cover"
      />
      <div
        v-else
        class="grid h-11 w-11 shrink-0 place-items-center rounded-lg text-lg font-bold text-white"
        style="background: var(--hero-gradient)"
        aria-hidden="true"
      >
        {{ item.name.charAt(0) }}
      </div>
      <div class="min-w-0 flex-1">
        <div class="flex items-start justify-between gap-2">
          <h3 class="font-semibold leading-snug">{{ item.name }}</h3>
          <div class="flex shrink-0 items-center gap-1">
            <Badge v-if="item.channel && item.channel !== 'stable'" tone="accent">
              {{ t(`channel.${item.channel}`) }}
            </Badge>
          </div>
        </div>
        <p class="truncate text-xs text-muted dark:text-slate-400">{{ item.namespace }}</p>
      </div>
    </div>

    <p class="line-clamp-2 text-sm leading-6 text-muted dark:text-slate-400">
      {{ item.summary }}
    </p>

    <div class="mt-auto space-y-1.5">
      <div v-if="item.category || (item.minBeeLevel && item.minBeeLevel > 0)" class="flex flex-wrap gap-1">
        <Badge v-if="item.category" tone="muted">{{ item.category }}</Badge>
        <BeeLevelBadge
          v-if="item.minBeeLevel && item.minBeeLevel > 0"
          :level="item.minBeeLevel"
          require
        />
      </div>
      <div class="flex items-center justify-between text-xs text-muted dark:text-slate-400">
        <span>{{ item.downloads != null ? formatNumber(item.downloads) : '—' }} {{ t('discover.statsDownloads') }}</span>
        <span class="flex items-center gap-2">
          <span v-if="item.latestVersion">v{{ item.latestVersion }}</span>
          <span class="font-medium text-ink/70 dark:text-slate-300">{{ typeLabel }}</span>
        </span>
      </div>
    </div>
  </RouterLink>
</template>
