<!--
  ProgressBar — port of Magic UI "progress" (MIT, magicuidesign/magicui).
  Determinate bar or indeterminate slide; indeterminate motion stops under
  prefers-reduced-motion (renders as a striped static bar).
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

const props = defineProps<{
  /** 0..100; omit for indeterminate. */
  value?: number;
}>();

const reduced = useReducedMotion();
const indeterminate = computed(() => props.value === undefined);
const animation = computed(() =>
  indeterminate.value && !reduced.value
    ? 'magic-progress-slide 1.2s ease-in-out infinite'
    : 'none',
);
</script>

<template>
  <div
    class="magic-progress"
    role="progressbar"
    :aria-valuenow="indeterminate ? undefined : value"
    :aria-valuemin="0"
    :aria-valuemax="100"
  >
    <div
      class="magic-progress__fill"
      :class="{ 'magic-progress-indeterminate': indeterminate }"
      :style="{ width: indeterminate ? '40%' : `${Math.max(0, Math.min(100, value ?? 0))}%`, animation }"
    />
  </div>
</template>

<style scoped>
.magic-progress {
  width: 100%;
  height: 0.5rem;
  border-radius: 9999px;
  background: rgb(var(--magic-muted) / 0.15);
  overflow: hidden;
}
.magic-progress__fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, rgb(var(--magic-accent)), rgb(var(--magic-accent-2)));
  transition: width 0.3s ease;
}
@keyframes magic-progress-slide {
  0% {
    margin-left: -40%;
  }
  100% {
    margin-left: 100%;
  }
}
</style>
