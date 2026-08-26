<!--
  BorderBeam — port of Magic UI "border-beam" (MIT, magicuidesign/magicui).
  A light beam travels around the border via a rotating conic gradient.
  Under prefers-reduced-motion the beam renders as a static gradient.
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

const props = withDefaults(
  defineProps<{
    /** Beam thickness in px. */
    size?: number;
    /** Animation duration in seconds. */
    duration?: number;
    /** Delay before starting, in seconds. */
    delay?: number;
    /** Reverse direction. */
    reverse?: boolean;
  }>(),
  { size: 2, duration: 6, delay: 0, reverse: false },
);

const reduced = useReducedMotion();
const animation = computed(() =>
  reduced.value
    ? 'none'
    : `magic-beam-rotate ${props.duration}s linear ${props.delay}s infinite ${
        props.reverse ? 'reverse' : 'normal'
      }`,
);
</script>

<template>
  <div class="magic-beam" :style="{ '--beam-size': `${size}px`, animation }" aria-hidden="true" />
</template>

<style scoped>
@property --beam-angle {
  syntax: '<angle>';
  inherits: false;
  initial-value: 0deg;
}

.magic-beam {
  position: absolute;
  inset: calc(var(--beam-size) * -1);
  border-radius: inherit;
  padding: var(--beam-size);
  background: conic-gradient(
    from var(--beam-angle),
    transparent 0%,
    rgb(var(--magic-accent)) 12%,
    rgb(var(--magic-accent-2)) 18%,
    transparent 30%
  );
  -webkit-mask:
    linear-gradient(#fff 0 0) content-box exclude,
    linear-gradient(#fff 0 0);
  mask:
    linear-gradient(#fff 0 0) content-box exclude,
    linear-gradient(#fff 0 0);
  pointer-events: none;
}

@keyframes magic-beam-rotate {
  to {
    --beam-angle: 360deg;
  }
}

@media (prefers-reduced-motion: reduce) {
  .magic-beam {
    background: conic-gradient(
      from 0deg,
      transparent 0%,
      rgb(var(--magic-accent)) 12%,
      rgb(var(--magic-accent-2)) 18%,
      transparent 30%
    );
  }
}
</style>
