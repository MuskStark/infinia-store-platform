<!--
  Badge — port of Magic UI "badge" (MIT, magicuidesign/magicui).
  Static presentational badge with soft accent background.
-->
<script setup lang="ts">
withDefaults(defineProps<{ tone?: 'accent' | 'muted' | 'success' | 'danger' }>(), {
  tone: 'accent',
});
</script>

<template>
  <span class="magic-badge" :class="`magic-badge--${tone}`">
    <slot />
  </span>
</template>

<style scoped>
.magic-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border-radius: 9999px;
  border: 1px solid;
  padding: 0.15rem 0.65rem;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.4;
}
.magic-badge--accent {
  color: rgb(var(--magic-accent));
  background: rgb(var(--magic-accent) / 0.08);
  border-color: rgb(var(--magic-accent) / 0.25);
}
.magic-badge--muted {
  color: rgb(var(--magic-muted));
  background: rgb(var(--magic-muted) / 0.1);
  border-color: rgb(var(--magic-muted) / 0.2);
}
.magic-badge--success {
  color: #15803d;
  background: #15803d14;
  border-color: #15803d33;
}
.magic-badge--danger {
  color: #b91c1c;
  background: #b91c1c14;
  border-color: #b91c1c33;
}
</style>
