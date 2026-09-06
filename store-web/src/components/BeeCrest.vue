<script setup lang="ts">
import { computed } from 'vue';
import { beeMark } from '../bee-levels';

/**
 * BeeCrest — the Infinia Level coat of arms. Each hive level is a different
 * silhouette, drawn from the same honeycomb language:
 *   larva  — an empty cell with the egg inside
 *   worker — the first comb cluster
 *   forager — the cell with a nectar drop
 *   guard  — a shield carrying the cell
 *   queen  — the crown above the cell (brand gradient)
 * Color comes from the tier via `color` so text and mark stay in sync.
 */
const props = withDefaults(defineProps<{ level: number; size?: number }>(), {
  size: 18,
});

const mark = computed(() => beeMark(props.level));
</script>

<template>
  <span class="bee-crest" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <!-- Larva: empty outlined cell + egg -->
    <svg v-if="mark.tier === 'larva'" viewBox="0 0 24 24" fill="none">
      <path
        d="M12 3.2 20.2 7.9v9.4L12 22 3.8 17.3V7.9L12 3.2Z"
        stroke="currentColor"
        stroke-width="1.7"
        stroke-linejoin="round"
      />
      <circle cx="12" cy="12.6" r="2.5" fill="currentColor" />
    </svg>

    <!-- Worker: the first comb cluster -->
    <svg v-else-if="mark.tier === 'worker'" viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 1.6 16.5 4.2v5.2L12 12 7.5 9.4V4.2L12 1.6Z" />
      <path d="M7.5 11.4 12 14v5.2L7.5 21.8 3 19.2v-5.2l4.5-2.6Z" />
      <path d="M16.5 11.4 21 14v5.2L16.5 21.8 12 19.2v-5.2l4.5-2.6Z" opacity=".55" />
    </svg>

    <!-- Forager: the cell with a nectar drop -->
    <svg v-else-if="mark.tier === 'forager'" viewBox="0 0 24 24">
      <path d="M12 2.4 20.6 7.3v9.8L12 22.1 3.4 17.1V7.3L12 2.4Z" fill="currentColor" />
      <path
        d="M12 7.2c1.7 2.1 2.9 3.5 2.9 4.9a2.9 2.9 0 1 1-5.8 0c0-1.4 1.2-2.8 2.9-4.9Z"
        fill="#fff"
      />
    </svg>

    <!-- Guard: the shield carrying the cell -->
    <svg v-else-if="mark.tier === 'guard'" viewBox="0 0 24 24">
      <path
        d="M12 1.8 20.4 4.4v7.2c0 5.1-3.4 8.5-8.4 10.4C7 20.1 3.6 16.7 3.6 11.6V4.4L12 1.8Z"
        fill="currentColor"
      />
      <path
        d="M12 7 16.4 9.5v5L12 17l-4.4-2.5v-5L12 7Z"
        fill="#fff"
      />
    </svg>

    <!-- Queen: the crown above the cell, filled with the brand sweep -->
    <svg v-else viewBox="0 0 24 24">
      <defs>
        <linearGradient id="bee-royal-grad" x1="0" y1="0" x2="1" y2="1">
          <stop stop-color="#fc801d" />
          <stop offset=".5" stop-color="#fe2857" />
          <stop offset="1" stop-color="#a73afd" />
        </linearGradient>
      </defs>
      <path
        d="M4.6 6.8 8.6 9.6 12 4.2l3.4 5.4 4-2.8-1.4 7.4H6L4.6 6.8Z"
        fill="url(#bee-royal-grad)"
      />
      <path
        d="M6.2 16.2h11.6l-.8 4.2H7l-.8-4.2Z"
        fill="url(#bee-royal-grad)"
        opacity=".85"
      />
    </svg>
  </span>
</template>

<style scoped>
.bee-crest {
  display: inline-flex;
  flex: none;
}
.bee-crest svg {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
