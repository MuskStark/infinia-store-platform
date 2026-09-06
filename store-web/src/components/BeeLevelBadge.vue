<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import BeeCrest from './BeeCrest.vue';
import { beeMark } from '../bee-levels';

/**
 * Infinia Level badge (等级徽章): every hive level carries its own crest — an
 * empty cell, the first comb, the nectar drop, the shield, the crown — so the
 * level is recognizable by silhouette alone, colored by its tier. `demands` mode
 * marks the minimum level a listing requires instead of the user's own;
 * `compact` drops the role name for tight spots (header chip).
 */
const props = withDefaults(
  defineProps<{ level: number; demands?: boolean; compact?: boolean }>(),
  { demands: false, compact: false },
);
const { t } = useI18n();

const safeLevel = computed(() => Math.max(0, Math.min(4, props.level)));
const mark = computed(() => beeMark(safeLevel.value));
const levelName = computed(() => t(`beeLevel.${safeLevel.value}`));
</script>

<template>
  <span
    class="bee-badge"
    :class="[`bee-badge--${mark.tier}`, { 'bee-badge--compact': compact }]"
    :title="t('beeLevel.title')"
  >
    <BeeCrest :level="safeLevel" :size="compact ? 15 : 18" />
    <span v-if="demands" class="bee-badge__label">
      {{ t('beeLevel.requires') }} {{ levelName }} (Lv{{ level }}+)
    </span>
    <span v-else-if="compact" class="bee-badge__label">Lv{{ level }}</span>
    <span v-else class="bee-badge__label">{{ levelName }} · Lv{{ level }}</span>
  </span>
</template>

<style scoped>
@reference '../styles/main.css';

/* No capsule: the crest itself is the badge, tier-colored, with its label. */
.bee-badge {
  @apply inline-flex items-center gap-1.5 whitespace-nowrap text-xs font-bold;
}
.bee-badge--larva {
  @apply text-muted;
}
.bee-badge--worker {
  @apply text-accent;
}
.bee-badge--forager {
  @apply text-success dark:text-emerald-400;
}
.bee-badge--guard {
  @apply text-warning dark:text-amber-400;
}
.bee-badge--queen .bee-badge__label {
  @apply bg-gradient-to-r from-jb-orange via-accent2 to-jb-purple bg-clip-text text-transparent;
}
.bee-badge--compact {
  @apply gap-1;
}
</style>
