import { useState } from 'react';
import { motion } from 'motion/react';
import { useTranslation } from 'react-i18next';
import { setLocale } from './i18n';
import StatusView from './views/StatusView';
export default function App() {
  const { t, i18n } = useTranslation();
  const [dark, setDark] = useState(document.documentElement.classList.contains('dark'));
  return <>
    <header className="header-bar"><div className="shell nav">
      <a className="identity" href="/"><img src="/infinia-logo.svg" alt="" /><strong>Infinia</strong><span>{t('app.statusTitle')}</span></a>
      <div className="actions">
        <button className="btn btn-ghost" onClick={() => setLocale(i18n.language === 'en' ? 'zh-CN' : 'en')}>{i18n.language === 'en' ? '中文' : 'EN'}</button>
        <button className="btn btn-ghost" aria-label={t('app.themeToggle')} onClick={() => {
          document.documentElement.classList.toggle('dark', !dark);
          window.localStorage.setItem('infinia.monitor.theme', dark ? 'light' : 'dark'); setDark(!dark);
        }}><motion.span key={String(dark)} initial={{ opacity: 0, rotate: -45 }} animate={{ opacity: 1, rotate: 0 }} aria-hidden="true">{dark ? '☀' : '☾'}</motion.span></button>
      </div>
    </div></header>
    <main className="shell"><StatusView /></main>
  </>;
}
