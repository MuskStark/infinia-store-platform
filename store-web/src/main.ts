import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import { i18n, setLocale } from './i18n';
// Library defaults first so the app stylesheet (loaded after) can override the
// :root tokens — including the .dark overrides. The reverse order lets the
// library's :root beat the app's .dark rules (equal specificity), breaking dark mode.
import '@infinia/magic-ui-vue/styles.css';
import './styles/main.css';

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.use(i18n);

// Restore persisted locale / theme before mount.
setLocale(i18n.global.locale.value as 'en' | 'zh-CN');
if (localStorage.getItem('infinia.store.theme') === 'dark') {
  document.documentElement.classList.add('dark');
}

app.mount('#app');
