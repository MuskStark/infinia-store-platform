import i18next, { type i18n as I18n } from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from '../locales/en';
import zhCN from '../locales/zh-CN';

export const SUPPORTED_LOCALES = ['en', 'zh-CN'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];
/** English is the primary language (design: EN first, zh-CN switchable). */
export const DEFAULT_LOCALE: Locale = 'en';
const STORAGE_KEY = 'infinia.store.locale';

export function detectLocale(): Locale {
  // Guarded: utils and tests import this module outside the browser too, where
  // localStorage/navigator may not exist and module init must not throw.
  const stored =
    typeof localStorage === 'undefined' ? null : (localStorage.getItem(STORAGE_KEY) ?? localStorage.getItem('infinia.website.locale'));
  if (stored && (SUPPORTED_LOCALES as readonly string[]).includes(stored)) {
    return stored as Locale;
  }
  for (const candidate of navigator?.languages ?? []) {
    if (candidate.toLowerCase().startsWith('zh')) {
      return 'zh-CN';
    }
  }
  return DEFAULT_LOCALE;
}

/**
 * vue-i18n compatibility: message strings use single-brace `{x}` placeholders
 * and the pipe plural form ('{count} rating | {count} ratings'). Every call
 * site passes named args only (never a plural argument), so vue-i18n always
 * rendered the FIRST pipe branch — flatten those here, and point i18next's
 * interpolation delimiters at the same single braces. The locale dictionaries
 * stay byte-identical to the vue-i18n era.
 */
function flattenPipeBranches(node: unknown): unknown {
  if (typeof node === 'string') {
    const pipe = node.indexOf('|');
    return pipe === -1 ? node : node.slice(0, pipe).trimEnd();
  }
  if (Array.isArray(node)) {
    return node.map(flattenPipeBranches);
  }
  if (node && typeof node === 'object') {
    return Object.fromEntries(
      Object.entries(node).map(([key, value]) => [key, flattenPipeBranches(value)]),
    );
  }
  return node;
}

/**
 * The i18next instance behind every `useTranslation()` call. The message
 * objects are the same nested dictionaries vue-i18n consumed; the default '.'
 * key separator keeps every `t('nav.discover')` key identical.
 */
export const i18n: I18n = i18next.createInstance();

void i18n.use(initReactI18next).init({
  lng: detectLocale(),
  fallbackLng: DEFAULT_LOCALE,
  resources: {
    en: { translation: flattenPipeBranches(en) as Record<string, unknown> },
    'zh-CN': { translation: flattenPipeBranches(zhCN) as Record<string, unknown> },
  },
  // Message strings use vue-i18n's {x} placeholder shape; the store never
  // escapes HTML, so disable escaping to keep the output identical.
  interpolation: { prefix: '{', suffix: '}', escapeValue: false },
  returnNull: false,
});

export function setLocale(locale: Locale) {
  void i18n.changeLanguage(locale);
  localStorage.setItem(STORAGE_KEY, locale);
  document.documentElement.lang = locale;
}
