<script setup lang="ts">
defineProps<{ title: string; hint?: string }>();
</script>

<template>
  <div
    class="grid place-items-center rounded-lg border border-dashed border-line px-6 py-10 text-center dark:border-slate-800"
  >
    <!-- Icon well: callers may slot their own mark; the stacked squares read as
         "nothing here yet" without pulling an icon library into the bundle. -->
    <div
      class="mb-3 grid h-11 w-11 place-items-center rounded-xl bg-surface-muted text-muted dark:bg-slate-800 dark:text-slate-400"
      aria-hidden="true"
    >
      <slot name="icon">
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
          <path
            d="M3.5 6.5L8 4l4 2.5L16.5 4v9.5L12 16l-4-2.5-4.5 2.5V6.5Z M8 6.5v7 M12 9.5v6.5"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </slot>
    </div>
    <p class="font-medium">{{ title }}</p>
    <p v-if="hint" class="mt-1 max-w-sm text-sm text-muted dark:text-slate-400">{{ hint }}</p>
    <div v-if="$slots.default" class="mt-4 flex items-center gap-2">
      <slot />
    </div>
  </div>
</template>
