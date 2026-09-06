<script setup lang="ts">
import { useI18n } from 'vue-i18n';

const { t } = useI18n();
defineProps<{ message?: string }>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <div
    role="alert"
    class="alert alert-error text-center"
  >
    <p class="font-medium">
      {{ message ?? t('common.error') }}
    </p>
    <button
      class="btn btn-secondary mx-auto mt-3"
      @click="$emit('retry')"
    >
      {{ t('common.retry') }}
    </button>
  </div>
</template>
