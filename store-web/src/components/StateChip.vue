<script setup lang="ts">
import { computed } from 'vue';
import { Badge } from '@infinia/magic-ui-vue';

/**
 * Release state chip. Never relies on color alone — the label is always visible
 * (design §12.6 accessibility rule).
 */
const props = withDefaults(defineProps<{ status?: string }>(), { status: '' });

const tone = computed(() => {
  switch (props.status) {
    case 'PUBLISHED':
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
    case 'QUARANTINED':
      return 'danger';
    default:
      return 'muted';
  }
});
</script>

<template>
  <Badge :tone="tone">{{ status }}</Badge>
</template>
