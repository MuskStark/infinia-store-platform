import { i18n } from '../i18n';

/** Locale-aware display formatters (same shapes as the store SPA). */

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

/** Same as formatDate plus hour/minute (mirror timestamps, incident times). */
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
