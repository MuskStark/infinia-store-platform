<!--
  AnimatedGridPattern — port of Magic UI "animated-grid-pattern" (MIT,
  magicuidesign/magicui). SVG grid whose squares drift via dash-offset;
  static under prefers-reduced-motion.
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

withDefaults(
  defineProps<{
    width?: number;
    height?: number;
    cellSize?: number;
    /** Animated square size in cells. */
    squares?: number;
    duration?: number;
  }>(),
  { width: 40, height: 24, cellSize: 40, squares: 3, duration: 4 },
);

const reduced = useReducedMotion();
const animation = computed(() => (reduced.value ? 'none' : undefined));
</script>

<template>
  <svg
    class="magic-grid"
    :width="width * cellSize"
    :height="height * cellSize"
    :viewBox="`0 0 ${width * cellSize} ${height * cellSize}`"
    aria-hidden="true"
    fill="none"
  >
    <defs>
      <pattern id="magic-grid-cells" :width="cellSize" :height="cellSize" patternUnits="userSpaceOnUse">
        <path
          :d="`M ${cellSize} 0 L 0 0 0 ${cellSize}`"
          fill="none"
          stroke="rgb(var(--magic-border))"
          stroke-width="1"
        />
      </pattern>
    </defs>
    <rect width="100%" height="100%" fill="url(#magic-grid-cells)" />
    <rect
      class="magic-grid-drift"
      :width="squares * cellSize"
      :height="squares * cellSize"
      :style="{ animation: animation ?? `magic-grid-dash ${duration}s linear infinite`, animationDuration: `${duration}s` }"
      fill="rgb(var(--magic-accent) / 0.08)"
      stroke="rgb(var(--magic-accent) / 0.35)"
      stroke-width="1"
    />
  </svg>
</template>

<style scoped>
.magic-grid {
  display: block;
  max-width: 100%;
}
@keyframes magic-grid-dash {
  0% {
    transform: translate(0, 0);
  }
  25% {
    transform: translate(5%, -8%);
  }
  50% {
    transform: translate(-4%, 6%);
  }
  75% {
    transform: translate(7%, 3%);
  }
  100% {
    transform: translate(0, 0);
  }
}
</style>
