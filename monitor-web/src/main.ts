import { createApp } from 'vue';
import App from './App.vue';
import { i18n } from './i18n';
import './styles/main.css';

const app = createApp(App);
app.use(i18n);

// Restore persisted locale / theme before mount (same pattern as the store SPA).
const locale = i18n.global.locale.value as 'en' | 'zh-CN';
document.documentElement.lang = locale;
if (localStorage.getItem('infinia.monitor.theme') === 'dark') {
  document.documentElement.classList.add('dark');
}

app.mount('#app');
