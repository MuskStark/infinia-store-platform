import { i18n } from '../i18n';

/**
 * Locale-aware display formatters. Every view renders dates and numbers through
 * these so the whole store shows one format that follows the app language
 * (design §12.2 tokens: same data, same presentation everywhere) instead of the
 * browser locale or raw ISO strings.
 */

function appLocale(): string {
  return i18n.global.locale.value;
}

function parse(iso?: string | null): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** 2026-09-03 → “Sep 3, 2026” / “2026年9月3日”；invalid or missing → “—”. */
export function formatDate(iso?: string | null): string {
  const date = parse(iso);
  if (!date) return '—';
  return new Intl.DateTimeFormat(appLocale(), {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(date);
}

/** Same as formatDate plus hour/minute; used for sessions, syncs and audit rows. */
export function formatDateTime(iso?: string | null): string {
  const date = parse(iso);
  if (!date) return '—';
  return new Intl.DateTimeFormat(appLocale(), {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/** Thousands separators follow the app locale, matching Intl dates above. */
export function formatNumber(value?: number | null): string {
  if (value == null) return '0';
  return new Intl.NumberFormat(appLocale()).format(value);
}
