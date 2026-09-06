<script setup lang="ts">
import { useI18n } from 'vue-i18n';
const { t } = useI18n();
</script>

<template>
  <div class="grid place-items-center gap-4 py-24 text-center">
    <p class="text-6xl font-extrabold">404</p>
    <p class="text-lg">{{ t('common.notFound') }}</p>
    <RouterLink class="btn btn-primary px-5" :to="{ name: 'discover' }">
      {{ t('common.backHome') }}
    </RouterLink>
  </div>
</template>
