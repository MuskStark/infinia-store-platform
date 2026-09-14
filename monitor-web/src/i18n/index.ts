import { createInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from '../locales/en';
import zhCN from '../locales/zh-CN';
export const SUPPORTED_LOCALES = ['en', 'zh-CN'] as const;
export type Locale = typeof SUPPORTED_LOCALES[number];
let stored: string | null = null;
try { stored = window.localStorage.getItem('infinia.monitor.locale'); } catch { /* Storage may be unavailable in private sessions. */ }
export const i18n = createInstance();
void i18n.use(initReactI18next).init({
  lng: stored === 'en' || stored === 'zh-CN' ? stored : navigator.language.startsWith('zh') ? 'zh-CN' : 'en',
  fallbackLng: 'en', resources: { en: { translation: en }, 'zh-CN': { translation: zhCN } },
  interpolation: { escapeValue: false, prefix: '{', suffix: '}' },
});
export function setLocale(locale: Locale) {
  void i18n.changeLanguage(locale);
  try { window.localStorage.setItem('infinia.monitor.locale', locale); } catch { /* Locale still applies for this session. */ }
  document.documentElement.lang = locale;
}
