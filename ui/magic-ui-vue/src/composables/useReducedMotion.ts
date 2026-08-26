import { ref, onMounted, onUnmounted, type Ref } from 'vue';

/**
 * Reactive `prefers-reduced-motion` matcher. Every decorative animation in this
 * port collapses to its static state when the user prefers reduced motion.
 */
export function useReducedMotion(): Ref<boolean> {
  const reduced = ref(false);
  let query: MediaQueryList | null = null;
  const update = () => {
    reduced.value = !!query?.matches;
  };

  onMounted(() => {
    if (typeof window === 'undefined' || !window.matchMedia) {
      return;
    }
    query = window.matchMedia('(prefers-reduced-motion: reduce)');
    update();
    query.addEventListener('change', update);
  });
  onUnmounted(() => {
    query?.removeEventListener('change', update);
  });
  return reduced;
}
