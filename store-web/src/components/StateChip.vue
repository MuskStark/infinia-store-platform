<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { Badge } from '@infinia/magic-ui-vue';

/**
 * Release state chip. Never relies on color alone — the label is always visible
 * (design §12.6 accessibility rule). The status enum is localized; unknown
 * values fall back to the raw status so new backend states never render blank.
 */
const props = withDefaults(defineProps<{ status?: string }>(), { status: '' });

const { t, te } = useI18n();

const tone = computed(() => {
  switch (props.status) {
    case 'PUBLISHED':
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
    case 'QUARANTINED':
    case 'YANKED':
      return 'danger';
    // In-flight states read as "the pipeline is moving" (blue), while
    // CHANGES_REQUESTED asks the publisher for input (gold) — muted gray made
    // both indistinguishable from DRAFT in the release history.
    case 'IN_REVIEW':
    case 'SCANNING':
    case 'UPLOADING':
      return 'accent';
    case 'CHANGES_REQUESTED':
      return 'gold';
    default:
      return 'muted';
  }
});

const label = computed(() =>
  props.status && te(`state.${props.status}`) ? t(`state.${props.status}`) : props.status,
);
</script>

<template>
  <Badge :tone="tone">{{ label }}</Badge>
</template>
