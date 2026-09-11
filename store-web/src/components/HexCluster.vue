<script setup lang="ts">
withDefaults(defineProps<{ color?: string }>(), { color: '#fc801d' });

/**
 * HexCluster — the honeycomb wash shared by the user center's hero and the
 * store home: three thin-stroked comb cells plus one small filled shard,
 * tinted by the caller (tier color there, brand orange here). Sized by the
 * parent container; keep it decorative and out of interaction.
 */
</script>

<template>
  <svg viewBox="0 0 240 240" fill="none" aria-hidden="true" class="pointer-events-none h-full w-full">
    <path
      d="M150 8 208 41v66l-58 33-58-33V41L150 8Z"
      :stroke="color"
      stroke-width="1.5"
      opacity=".16"
    />
    <path
      d="M88 96 128 119v46l-40 23-40-23v-46l40-23Z"
      :stroke="color"
      stroke-width="1.5"
      opacity=".24"
    />
    <path
      d="M170 138 196 153v30l-26 15-26-15v-30l26-15Z"
      :stroke="color"
      stroke-width="1.5"
      opacity=".1"
    />
    <path d="M88 119v23l20 11.5v-23L88 119Z" :fill="color" opacity=".14" />
  </svg>
</template>
