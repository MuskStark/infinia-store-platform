<!--
  Marquee — port of Magic UI "marquee" (MIT, magicuidesign/magicui).
  Infinite horizontal scroll by translating a duplicated track; pauses on hover
  and is static under prefers-reduced-motion.
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

const props = withDefaults(
  defineProps<{
    duration?: number;
    reverse?: boolean;
    pauseOnHover?: boolean;
  }>(),
  { duration: 30, reverse: false, pauseOnHover: true },
);

const reduced = useReducedMotion();
const animation = computed(() =>
  reduced.value
    ? 'none'
    : `magic-marquee ${props.duration}s linear infinite ${props.reverse ? 'reverse' : 'normal'}`,
);
</script>

<template>
  <div class="magic-marquee" :class="{ 'magic-marquee--hover-pause': pauseOnHover && !reduced }">
    <div class="magic-marquee-track" :style="{ animation }">
      <div class="magic-marquee__group">
        <slot />
      </div>
      <div class="magic-marquee__group" aria-hidden="true">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.magic-marquee {
  overflow: hidden;
  position: relative;
  display: flex;
  mask-image: linear-gradient(to right, transparent, black 8%, black 92%, transparent);
}
.magic-marquee-track {
  display: flex;
  flex-shrink: 0;
  gap: 1rem;
  min-width: 100%;
  width: max-content;
}
.magic-marquee__group {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding-right: 1rem;
}
.magic-marquee--hover-pause:hover .magic-marquee-track {
  animation-play-state: paused;
}
@keyframes magic-marquee {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(-50%);
  }
}
</style>
