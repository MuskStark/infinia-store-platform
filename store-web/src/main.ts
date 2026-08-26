import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import { i18n, setLocale } from './i18n';
import './styles/main.css';
import '@infinia/magic-ui-vue/styles.css';

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
