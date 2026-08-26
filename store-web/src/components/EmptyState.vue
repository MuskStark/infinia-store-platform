<script setup lang="ts">
defineProps<{ title: string; hint?: string }>();
</script>

<template>
  <div
    class="grid place-items-center rounded-2xl border border-dashed border-line p-12 text-center dark:border-slate-800"
  >
    <p class="font-medium">{{ title }}</p>
    <p v-if="hint" class="mt-1 text-sm text-muted dark:text-slate-400">{{ hint }}</p>
    <slot />
  </div>
</template>
