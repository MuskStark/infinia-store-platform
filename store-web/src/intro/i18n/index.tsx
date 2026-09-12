import { useTranslation } from 'react-i18next';
import { setLocale } from '@/i18n';
import { en } from './en';
import { zhCN } from './zh-CN';
export type { Locale } from '@/i18n';

/** Introduction and store share a single persisted language preference. */
export function useLocale() {
  const { i18n } = useTranslation();
  const locale = i18n.language === 'zh-CN' ? 'zh-CN' : 'en';
  return { locale, t: locale === 'zh-CN' ? zhCN : en, setLocale };
}
