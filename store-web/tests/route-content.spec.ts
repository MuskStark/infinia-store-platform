import { describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import RouteContent from '../src/components/RouteContent.vue';
import en from '../src/locales/en';

describe('route content recovery', () => {
  it('shows an error when a route chunk fails, then recovers on navigation', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { template: '<p>Catalog content</p>' } },
      { path: '/broken', component: () => Promise.reject(new Error('Failed to fetch dynamically imported module')) },
    ] });
    await router.push('/');
    const wrapper = mount(RouteContent, { global: { plugins: [router,
      createI18n({ legacy: false, locale: 'en', messages: { en } }),
    ] } });
    await router.push('/broken').catch(() => {});
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain(en.common.pageLoadError);
    expect(wrapper.get('button').text()).toBe('Retry');
    await router.push('/?recovered=1');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('Catalog content');
    wrapper.unmount();
  });

  it('displays loading while the first route module is pending', async () => {
    let finish!: (value: { template: string }) => void;
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: () => new Promise<{ template: string }>((resolve) => { finish = resolve; }) },
    ] });
    const wrapper = mount(RouteContent, { global: { plugins: [router,
      createI18n({ legacy: false, locale: 'en', messages: { en } }),
    ] } });
    await flushPromises();
    expect(wrapper.get('[role="status"]').text()).toBe(en.common.loading);
    finish({ template: '<p>Loaded catalog</p>' });
    await router.isReady();
    await flushPromises();
    expect(wrapper.find('[role="status"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('Loaded catalog');
    wrapper.unmount();
  });
});
