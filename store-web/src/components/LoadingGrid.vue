<script setup lang="ts">
defineProps<{ count?: number }>();
const defaultCount = 6;
</script>

<template>
  <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true">
    <div
      v-for="i in count ?? defaultCount"
      :key="i"
      class="h-44 animate-pulse rounded-2xl bg-surface dark:bg-slate-900"
    />
  </div>
</template>
