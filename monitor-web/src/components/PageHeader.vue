<script setup lang="ts">
/**
 * Shared page header: h1 + optional subtitle on the left, page-level actions on
 * the right. Every top-level view opens with this so titles, spacing and action
 * alignment stay identical across the store.
 */
defineProps<{ title: string; subtitle?: string }>();
</script>

<template>
  <header class="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
    <div class="min-w-0">
      <h1 class="text-2xl font-bold tracking-tight">{{ title }}</h1>
      <p v-if="subtitle" class="mt-1.5 max-w-3xl text-sm text-muted dark:text-slate-400">
        {{ subtitle }}
      </p>
    </div>
    <div v-if="$slots.actions" class="flex flex-wrap items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
