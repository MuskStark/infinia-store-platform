<!--
  BlurFade — port of Magic UI "blur-fade" (MIT, magicuidesign/magicui).
  Fades content in with a blur transition on mount; instant when the user
  prefers reduced motion.
-->
<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useReducedMotion } from '../composables/useReducedMotion';

const props = withDefaults(
  defineProps<{ delay?: number; duration?: number; blur?: string }>(),
  { delay: 0, duration: 0.6, blur: '8px' },
);

const reduced = useReducedMotion();
const visible = ref(false);

onMounted(() => {
  if (reduced.value) {
    visible.value = true;
    return;
  }
  window.setTimeout(() => {
    visible.value = true;
  }, props.delay * 1000);
});
</script>

<template>
  <div
    class="magic-blur-fade"
    :class="{ 'magic-blur-fade--visible': visible }"
    :style="{
      transitionDuration: `${duration}s`,
      '--fade-blur': blur,
    }"
  >
    <slot />
  </div>
</template>

<style scoped>
.magic-blur-fade {
  opacity: 0;
  filter: blur(var(--fade-blur));
  transform: translateY(4px);
  transition:
    opacity,
    filter,
    transform;
  transition-timing-function: cubic-bezier(0.22, 1, 0.36, 1);
}
.magic-blur-fade--visible {
  opacity: 1;
  filter: blur(0);
  transform: none;
}
</style>
