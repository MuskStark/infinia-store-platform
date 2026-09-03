import { createI18n } from 'vue-i18n';
import en from '../locales/en';
import zhCN from '../locales/zh-CN';

export const SUPPORTED_LOCALES = ['en', 'zh-CN'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];
/** English is the primary language (design: EN first, zh-CN switchable). */
export const DEFAULT_LOCALE: Locale = 'en';
const STORAGE_KEY = 'infinia.store.locale';

function detectLocale(): Locale {
  // Guarded: utils and tests import this module outside the browser too, where
  // localStorage/navigator may not exist and module init must not throw.
  const stored =
    typeof localStorage === 'undefined' ? null : localStorage.getItem(STORAGE_KEY);
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

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: DEFAULT_LOCALE,
  messages: {
    en,
    'zh-CN': zhCN,
  },
});

export function setLocale(locale: Locale) {
  i18n.global.locale.value = locale;
  localStorage.setItem(STORAGE_KEY, locale);
  document.documentElement.lang = locale;
}
