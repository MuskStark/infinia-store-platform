<!--
  AnimatedList — port of Magic UI "animated-list" (MIT, magicuidesign/magicui).
  Staggered fade/slide-in of the provided items; instant under reduced motion.
-->
<script setup lang="ts">
import { useReducedMotion } from '../composables/useReducedMotion';

withDefaults(defineProps<{ items: unknown[]; stagger?: number }>(), { stagger: 0.08 });

const reduced = useReducedMotion();
</script>

<template>
  <ul class="magic-list" role="list">
    <li
      v-for="(item, index) in items"
      :key="index"
      class="magic-list-item"
      :style="{
        animation: reduced
          ? 'none'
          : `magic-list-in 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${index * stagger}s both`,
      }"
    >
      <slot :item="item" :index="index" />
    </li>
  </ul>
</template>

<style scoped>
@keyframes magic-list-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
    filter: blur(4px);
  }
  to {
    opacity: 1;
    transform: none;
    filter: none;
  }
}
</style>
