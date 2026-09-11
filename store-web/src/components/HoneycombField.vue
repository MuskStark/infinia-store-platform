<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

/**
 * HoneycombField — a full-bleed wall of thin-stroked comb cells with a few
 * wax-filled accents, tinted by the caller. Pure decoration: it renders an
 * SVG sized by its parent (slice-fit) and never interacts.
 *
 * Geometry: pointy-top hexagons, odd rows offset by half a cell width, so a
 * caller just picks a wall size (rows × cols) and which cells are waxed.
 */
const props = withDefaults(
  defineProps<{
    /** Stroke color for the comb outlines. */
    color?: string;
    /** Fill color for the waxed (accent) cells. */
    waxColor?: string;
    rows?: number;
    cols?: number;
    /** Hexagon circumradius in viewBox units. */
    cell?: number;
    /** Linear indexes (row × cols + col) of cells rendered as filled wax. */
    waxed?: number[];
    /** Randomly light cells for a living-wall feel; skipped under
     *  prefers-reduced-motion. */
    animated?: boolean;
  }>(),
  {
    color: 'rgba(252, 128, 29, 0.30)',
    waxColor: '#fc801d',
    rows: 5,
    cols: 6,
    cell: 46,
    waxed: () => [7, 16],
    animated: false,
  },
);

const W = computed(() => Math.sqrt(3) * props.cell);
const ROW_STEP = computed(() => 1.5 * props.cell);

/** Pointy-top hexagon outline around (cx, cy). */
function points(cx: number, cy: number): string {
  const r = props.cell;
  return [
    [cx, cy - r],
    [cx + (Math.sqrt(3) / 2) * r, cy - r / 2],
    [cx + (Math.sqrt(3) / 2) * r, cy + r / 2],
    [cx, cy + r],
    [cx - (Math.sqrt(3) / 2) * r, cy + r / 2],
    [cx - (Math.sqrt(3) / 2) * r, cy - r / 2],
  ]
    .map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`)
    .join(' ');
}

const cells = computed(() => {
  const out: { points: string; waxed: boolean }[] = [];
  for (let row = 0; row < props.rows; row++) {
    for (let col = 0; col < props.cols; col++) {
      const cx = W.value * col + (row % 2 ? W.value / 2 : 0) + W.value / 2;
      const cy = ROW_STEP.value * row + props.cell;
      out.push({
        points: points(cx, cy),
        waxed: props.waxed.includes(row * props.cols + col),
      });
    }
  }
  return out;
});

const lit = ref<Set<number>>(new Set());
let lightTimer: number | undefined;

function lightTick() {
  const next = new Set<number>();
  const count = 1 + Math.floor(Math.random() * 2);
  for (let i = 0; i < count; i++) {
    const index = Math.floor(Math.random() * cells.value.length);
    if (!props.waxed.includes(index)) {
      next.add(index);
    }
  }
  lit.value = next;
}

onMounted(() => {
  if (!props.animated || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return;
  }
  lightTimer = window.setInterval(lightTick, 750);
});
onBeforeUnmount(() => {
  if (lightTimer !== undefined) {
    window.clearInterval(lightTimer);
  }
});

const viewBox = computed(() => {
  const width = W.value * props.cols + W.value / 2;
  const height = ROW_STEP.value * (props.rows - 1) + 2 * props.cell;
  return `0 0 ${width.toFixed(2)} ${height.toFixed(2)}`;
});
</script>

<template>
  <svg
    class="pointer-events-none h-full w-full"
    :viewBox="viewBox"
    preserveAspectRatio="xMidYMid slice"
    aria-hidden="true"
  >
    <polygon
      v-for="(cell, index) in cells"
      :key="index"
      class="comb-cell"
      :points="cell.points"
      :fill="waxColor"
      :fill-opacity="cell.waxed ? 1 : lit.has(index) ? 0.55 : 0"
      :stroke="color"
      stroke-width="1.6"
      stroke-linejoin="round"
    />
  </svg>
</template>

<style scoped>
.comb-cell {
  transition: fill-opacity 1.2s ease;
}
@media (prefers-reduced-motion: reduce) {
  .comb-cell {
    transition: none;
  }
}
</style>
