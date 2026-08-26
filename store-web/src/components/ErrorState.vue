<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
defineProps<{ message?: string }>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <div
    role="alert"
    class="rounded-2xl border border-red-200 bg-red-50 p-6 text-center dark:border-red-900 dark:bg-red-950/40"
  >
    <p class="font-medium text-red-700 dark:text-red-300">
      {{ message ?? t('common.error') }}
    </p>
    <button
      class="mt-3 rounded-xl border border-red-300 px-4 py-2 text-sm dark:border-red-800"
      @click="$emit('retry')"
    >
      {{ t('common.retry') }}
    </button>
  </div>
</template>
