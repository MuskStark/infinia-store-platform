<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';

interface SelectOption {
  value: string | number;
  label: string;
}

/**
 * Styled single-select (design §12.2 tokens). Native <select> popups are drawn
 * by the OS and clash with the store's surface/border/dark palette, so menus
 * that sit inside product UI use this listbox instead: a trigger button plus a
 * popover with hover, check mark, click-outside and full keyboard support
 * (Enter/Space/Arrow keys/Escape, design §12.6).
 *
 * The popover renders position: fixed at the trigger's viewport coordinates so
 * scroll containers (e.g. the admin tables' overflow-x-auto wrappers) can never
 * clip it; it flips above the trigger when the viewport has no room below and
 * closes on scroll/resize instead of detaching from its anchor.
 */
const props = defineProps<{
  modelValue: string | number;
  options: SelectOption[];
  ariaLabel?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{ (e: 'update:modelValue', value: string | number): void }>();

const open = ref(false);
const activeIndex = ref(-1);
const root = ref<HTMLElement | null>(null);
const listRef = ref<HTMLElement | null>(null);
const listStyle = ref<Record<string, string>>({});

const current = computed(() => props.options.find((option) => option.value === props.modelValue));

function choose(option: SelectOption) {
  emit('update:modelValue', option.value);
  open.value = false;
}

async function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
  if (!open.value) {
    activeIndex.value = -1;
    return;
  }
  activeIndex.value = props.options.findIndex((option) => option.value === props.modelValue);
  // Measured in the same tick the popover mounts, so positioning lands before paint.
  await nextTick();
  const list = listRef.value;
  const anchor = root.value;
  if (!list || !anchor) return;
  const anchorRect = anchor.getBoundingClientRect();
  const listHeight = list.getBoundingClientRect().height;
  const spaceBelow = window.innerHeight - anchorRect.bottom;
  const spaceAbove = anchorRect.top;
  const dropUp = spaceBelow < listHeight + 8 && spaceAbove > spaceBelow;
  listStyle.value = {
    left: `${anchorRect.left}px`,
    top: dropUp ? `${anchorRect.top - listHeight - 4}px` : `${anchorRect.bottom + 4}px`,
    minWidth: `${anchorRect.width}px`,
  };
}

function onKeydown(event: KeyboardEvent) {
  if (props.disabled) return;
  if (!open.value) {
    if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      void toggle();
    }
    return;
  }
  switch (event.key) {
    case 'Escape':
      open.value = false;
      break;
    case 'ArrowDown':
      event.preventDefault();
      activeIndex.value = Math.min(activeIndex.value + 1, props.options.length - 1);
      break;
    case 'ArrowUp':
      event.preventDefault();
      activeIndex.value = Math.max(activeIndex.value - 1, 0);
      break;
    case 'Enter':
    case ' ': {
      event.preventDefault();
      const option = props.options[activeIndex.value];
      if (option) choose(option);
      break;
    }
  }
}

/** Close on outside click so the popover behaves like the account menu. */
function onDocumentClick(event: MouseEvent) {
  if (open.value && root.value && !root.value.contains(event.target as Node)) {
    open.value = false;
  }
}

/** A fixed popover can't follow its anchor through scrolls or resizes — close instead. */
function onViewportChange() {
  if (open.value) open.value = false;
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick);
  window.addEventListener('scroll', onViewportChange, true);
  window.addEventListener('resize', onViewportChange);
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick);
  window.removeEventListener('scroll', onViewportChange, true);
  window.removeEventListener('resize', onViewportChange);
});
</script>

<template>
  <div ref="root" class="relative" @keydown="onKeydown">
    <!-- Trigger: either a caller-styled control (#trigger slot, e.g. a badge
         that edits in place) or the default compact text button. -->
    <button
      v-if="$slots.trigger"
      type="button"
      :disabled="disabled"
      class="inline-flex min-h-0 items-center rounded-full transition hover:opacity-85 disabled:cursor-not-allowed disabled:opacity-50"
      :aria-label="ariaLabel"
      aria-haspopup="listbox"
      :aria-expanded="open"
      @click="toggle"
    >
      <slot name="trigger" />
    </button>
    <button
      v-else
      type="button"
      :disabled="disabled"
      class="inline-flex min-h-0 w-full items-center justify-between gap-1.5 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-xs hover:bg-surface-muted disabled:opacity-50 dark:border-slate-800 dark:bg-slate-900 dark:hover:bg-slate-800"
      :aria-label="ariaLabel"
      aria-haspopup="listbox"
      :aria-expanded="open"
      @click="toggle"
    >
      <span class="truncate">{{ current?.label ?? '—' }}</span>
      <span class="shrink-0 text-muted" aria-hidden="true">▾</span>
    </button>
    <ul
      v-if="open"
      ref="listRef"
      class="fixed z-50 max-h-64 w-max overflow-y-auto rounded-xl border border-line bg-surface py-1 shadow-xl dark:border-slate-800 dark:bg-slate-900"
      :style="listStyle"
      role="listbox"
    >
      <li v-for="(option, index) in options" :key="option.value">
        <button
          type="button"
          role="option"
          :aria-selected="option.value === modelValue"
          class="flex min-h-0 w-full items-center gap-2 whitespace-nowrap px-3 py-1.5 text-left text-xs"
          :class="option.value === modelValue
            ? 'font-semibold text-accent'
            : index === activeIndex
              ? 'bg-surface-muted dark:bg-slate-800'
              : ''"
          @click="choose(option)"
          @mouseenter="activeIndex = index"
        >
          <span class="w-3.5 shrink-0" aria-hidden="true">{{ option.value === modelValue ? '✓' : '' }}</span>
          <span>{{ option.label }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>
