<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Badge } from '@infinia/magic-ui-vue';
import { beeMark } from '../bee-levels';

/**
 * Bee ladder badge (蜜蜂等级): each hive level carries its own mark — a distinct
 * emblem and tone that change with the level, so a 🥚 larva, a 🐝 worker, a 🍯
 * forager, a 🛡 guard and the 👑 queen are recognizable at a glance. `demands`
 * mode marks the minimum level a listing requires instead of the user's own.
 */
const props = withDefaults(
  defineProps<{ level: number; demands?: boolean }>(),
  { demands: false },
);
const { t } = useI18n();

const safeLevel = computed(() => Math.max(0, Math.min(4, props.level)));
const mark = computed(() => beeMark(safeLevel.value));
const levelName = computed(() => t(`beeLevel.${safeLevel.value}`));
</script>

<template>
  <Badge :tone="mark.tone" :title="t('beeLevel.title')">
    <template v-if="demands">
      {{ t('beeLevel.requires') }} {{ mark.emblem }} {{ levelName }} (Lv{{ level }}+)
    </template>
    <template v-else>
      {{ mark.emblem }} {{ levelName }} · Lv{{ level }}
    </template>
  </Badge>
</template>
