import { createRoot } from 'react-dom/client';
import { MotionConfig } from 'motion/react';
import App from './App';
import { i18n } from './i18n';
import './styles/main.css';
document.documentElement.lang = i18n.language;
document.documentElement.classList.toggle('dark', window.localStorage.getItem('infinia.monitor.theme') === 'dark');
createRoot(document.getElementById('app')!).render(<MotionConfig reducedMotion="user"><App /></MotionConfig>);
