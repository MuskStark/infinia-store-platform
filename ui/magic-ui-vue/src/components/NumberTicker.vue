<!--
  NumberTicker — port of Magic UI "number-ticker" (MIT, magicuidesign/magicui).
  Counts up to the value with requestAnimationFrame; renders the final value
  immediately when the user prefers reduced motion.
-->
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

const props = withDefaults(
  defineProps<{ value: number; duration?: number; decimals?: number }>(),
  { duration: 1.6, decimals: 0 },
);

const reduced = useReducedMotion();
const displayed = ref(0);
let frame = 0;

function animateTo(target: number) {
  cancelAnimationFrame(frame);
  if (reduced.value) {
    displayed.value = target;
    return;
  }
  const from = displayed.value;
  const start = performance.now();
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / (props.duration * 1000));
    const eased = 1 - Math.pow(1 - t, 3);
    displayed.value = from + (target - from) * eased;
    if (t < 1) {
      frame = requestAnimationFrame(step);
    }
  };
  frame = requestAnimationFrame(step);
}

onMounted(() => animateTo(props.value));
watch(() => props.value, (value) => animateTo(value));
</script>

<template>
  <span class="magic-number-ticker" aria-live="off">{{ displayed.toFixed(decimals) }}</span>
</template>
