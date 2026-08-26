<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { MagicCard, Badge } from '@infinia/magic-ui-vue';
import type { CatalogItem } from '../api/client';

const props = defineProps<{ item: CatalogItem; featured?: boolean }>();
const { t } = useI18n();

const href = computed(
  () => `/listing/${props.item.namespace}/${props.item.slug}`,
);
const typeLabel = computed(() => t(`type.${props.item.type}`));
</script>

<template>
  <RouterLink :to="href" class="group block h-full">
    <MagicCard class="h-full transition-transform group-hover:-translate-y-0.5" :bordered="featured">
      <div class="flex h-full flex-col gap-3 p-5">
        <div class="flex items-start justify-between gap-2">
          <div class="grid h-10 w-10 place-items-center rounded-xl bg-surface-muted font-bold dark:bg-slate-800">
            {{ item.name.charAt(0) }}
          </div>
          <div class="flex items-center gap-1">
            <Badge tone="muted">{{ typeLabel }}</Badge>
            <Badge v-if="item.channel && item.channel !== 'stable'" tone="accent">
              {{ t(`channel.${item.channel}`) }}
            </Badge>
          </div>
        </div>
        <div>
          <h3 class="font-semibold leading-tight">{{ item.name }}</h3>
          <p class="mt-1 line-clamp-2 text-sm text-muted dark:text-slate-400">
            {{ item.summary }}
          </p>
        </div>
        <div class="mt-auto flex items-center justify-between text-xs text-muted dark:text-slate-400">
          <span>{{ item.namespace }}</span>
          <span v-if="item.latestVersion">v{{ item.latestVersion }}</span>
        </div>
      </div>
    </MagicCard>
  </RouterLink>
</template>
