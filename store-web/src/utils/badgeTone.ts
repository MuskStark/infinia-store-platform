import type { CSSProperties } from 'react';

/**
 * Soft-tone looks for the original shadcn Badge. The badge component itself
 * stays upstream-untouched; call sites pick a tone class (and, for gold, an
 * inline gradient) from this map.
 *
 * Brand and info are deliberately separate (plan §3.1): `brand` marks
 * selection / identity; `info` is an in-flight business state (scanning,
 * uploading, in review) — never gold. `accent` stays as an alias of `info`
 * for existing call sites that meant "in flight", not "brand".
 */
export const badgeToneClass: Record<string, string> = {
  brand: 'border-accent/30 bg-accent/10 text-accent',
  info: 'border-info/30 bg-info/10 text-info',
  accent: 'border-info/30 bg-info/10 text-info',
  muted: 'border-muted/20 bg-muted/10 text-muted',
  warning: 'border-warning/30 bg-warning/10 text-warning',
  success: 'border-success/25 bg-success/10 text-success',
  danger: 'border-danger/30 bg-danger/10 text-danger',
  // Royal gold — reserved for the top bee level (蜂王) and selection marks.
  gold: 'border-amber-600/40 from-amber-200/30 to-amber-500/15 text-amber-700 dark:text-amber-400',
};

export const badgeToneStyle: Partial<Record<keyof typeof badgeToneClass, CSSProperties>> = {
  gold: { backgroundImage: 'linear-gradient(135deg, rgba(253,230,138,.3), rgba(245,158,11,.15))' },
};

/** Base soft-badge shape matching the store's pill vocabulary. */
export const badgeBaseClass = 'rounded-full';
