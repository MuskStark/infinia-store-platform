import '@testing-library/jest-dom/vitest';
import { i18n } from '../src/i18n';

// jsdom lacks matchMedia; the sign-in wall's reduced-motion check and the
// reduced-motion branches probe it.
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

// jsdom lacks IntersectionObserver; Motion's useInView (BlurFade /
// NumberTicker) only constructs one — a no-op stub keeps mount-based
// animations working deterministically in tests.
if (typeof window !== 'undefined' && !('IntersectionObserver' in window)) {
  class IntersectionObserverStub {
    root = null;
    rootMargin = '';
    thresholds = [];
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return [];
    }
  }
  (window as unknown as { IntersectionObserver: unknown }).IntersectionObserver =
    IntersectionObserverStub;
}

// The store is English-first; keep every spec on the en dictionary regardless
// of the host machine's navigator.languages.
await i18n.changeLanguage('en');
