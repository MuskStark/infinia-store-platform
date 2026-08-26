<!--
  ShimmerButton — port of Magic UI "shimmer-button" (MIT, magicuidesign/magicui).
  A sheen sweeps across the button surface; static under reduced motion.
  Never replaces loading/disabled states — callers still render those.
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

withDefaults(defineProps<{ shimmerDuration?: number }>(), { shimmerDuration: 2.5 });

const reduced = useReducedMotion();
const animation = computed(() =>
  reduced.value ? 'none' : `magic-shimmer ${shimmerDuration ?? 2.5}s linear infinite`,
);
const shimmerDuration = 2.5;
</script>

<template>
  <button type="button" class="magic-shimmer-btn">
    <span class="magic-shimmer" :style="{ animation }" aria-hidden="true" />
    <span class="magic-shimmer-btn__label"><slot /></span>
  </button>
</template>

<style scoped>
.magic-shimmer-btn {
  position: relative;
  overflow: hidden;
  border: 0;
  border-radius: calc(var(--magic-radius) * 0.7);
  padding: 0.65rem 1.4rem;
  cursor: pointer;
  color: white;
  background: linear-gradient(110deg, rgb(var(--magic-accent)), rgb(var(--magic-accent-2)));
  box-shadow: 0 8px 24px -10px rgb(var(--magic-accent) / 0.6);
  font-weight: 600;
}
.magic-shimmer-btn__label {
  position: relative;
  z-index: 1;
}
.magic-shimmer {
  position: absolute;
  inset: -100% 0;
  background: linear-gradient(
    to bottom,
    transparent,
    rgba(255, 255, 255, 0.35),
    transparent
  );
  transform: translateY(-100%);
}
@keyframes magic-shimmer {
  0% {
    transform: translateY(-100%);
  }
  60%,
  100% {
    transform: translateY(100%);
  }
}
</style>
