<!--
  MagicCard — port of Magic UI "magic-card" (MIT, magicuidesign/magicui).
  Gradient border with a spotlight that follows the pointer; pure CSS + one
  pointermove handler. Reduced motion is irrelevant here (no animation).
-->
<script setup lang="ts">
import { ref } from 'vue';

defineProps<{
  /** Show the gradient border glow. */
  bordered?: boolean;
}>();

const card = ref<HTMLElement | null>(null);
const spotlight = ref({ x: 50, y: 50, active: false });

function onPointerMove(event: PointerEvent) {
  const el = card.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  spotlight.value = {
    x: ((event.clientX - rect.left) / rect.width) * 100,
    y: ((event.clientY - rect.top) / rect.height) * 100,
    active: true,
  };
}

function onPointerLeave() {
  spotlight.value = { ...spotlight.value, active: false };
}
</script>

<template>
  <div
    ref="card"
    class="magic-card"
    :class="{ 'magic-card--bordered': bordered }"
    @pointermove="onPointerMove"
    @pointerleave="onPointerLeave"
  >
    <div
      class="magic-card__spot"
      :style="{
        background: spotlight.active
          ? `radial-gradient(240px circle at ${spotlight.x}% ${spotlight.y}%, rgb(var(--magic-accent) / 0.14), transparent 70%)`
          : 'none',
      }"
      aria-hidden="true"
    />
    <div class="magic-card__content">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.magic-card {
  position: relative;
  overflow: hidden;
  border-radius: var(--magic-radius);
  background: rgb(var(--magic-surface));
  border: 1px solid rgb(var(--magic-border));
  isolation: isolate;
}
.magic-card--bordered {
  border: 0;
  background:
    linear-gradient(rgb(var(--magic-surface)), rgb(var(--magic-surface))) padding-box,
    linear-gradient(120deg, rgb(var(--magic-accent) / 0.7), rgb(var(--magic-accent-2) / 0.5))
      border-box;
  border: 1.5px solid transparent;
}
.magic-card__spot {
  position: absolute;
  inset: 0;
  pointer-events: none;
  transition: opacity 0.3s ease;
}
.magic-card__content {
  position: relative;
  height: 100%;
}
</style>
