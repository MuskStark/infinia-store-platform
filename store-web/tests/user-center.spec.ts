import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from '../src/stores/auth';
import AccountView from '../src/views/AccountView.vue';
import en from '../src/locales/en';

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

vi.mock('../src/api/client', () => ({
  api: {
    get: vi.fn(async (path: string) => {
      switch (path) {
        case '/api/v1/me':
          return {
            userId: 'u1',
            email: 'bee@example.com',
            displayName: 'Busy Bee',
            roles: ['USER', 'PUBLISHER'],
            beeLevel: 2,
            createdAt: '2026-08-01T00:00:00Z',
          };
        case '/api/v1/me/library':
          return {
            favorites: [
              {
                listingCoordinate: 'infinia://plugin/official/markdown',
                name: 'Markdown Tools',
                type: 'PLUGIN',
                latestVersion: '2.4.0',
                addedAt: '2026-08-20T10:00:00Z',
              },
            ],
            entitlements: [{ listingCoordinate: 'x', free: true, acquiredAt: 'z' }],
            installHistory: [],
          };
        case '/api/v1/organizations':
          return [{ organizationId: 'o1', slug: 'official', name: 'Infinia Official' }];
        case '/api/v1/me/sessions':
          return [{ sessionId: 's1', clientId: 'store-web', kind: 'PASSWORD', createdAt: 't' }];
        case '/api/v1/me/devices':
          return [];
        default:
          return [];
      }
    }),
    put: vi.fn(async () => undefined),
    delete: vi.fn(async () => undefined),
  },
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
}));

const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
};

async function mountCenter() {
  const pinia = createPinia();
  setActivePinia(pinia);
  // The view reads roles from the auth store for quick links.
  const auth = useAuthStore(pinia);
  auth.user = {
    userId: 'u1',
    email: 'bee@example.com',
    displayName: 'Busy Bee',
    roles: ['USER', 'PUBLISHER'],
    beeLevel: 2,
    createdAt: '2026-08-01T00:00:00Z',
  } as never;
  const wrapper = mount(AccountView, {
    global: {
      plugins: [i18n, pinia],
      stubs: { RouterLink: RouterLinkStub },
    },
  });
  await flushPromises();
  return wrapper;
}

describe('User Center (用户中心)', () => {
  it('renders the overview: identity, level badge and next-level hint', async () => {
    const wrapper = await mountCenter();
    expect(wrapper.text()).toContain('User Center');
    expect(wrapper.text()).toContain('Busy Bee');
    expect(wrapper.text()).toContain('bee@example.com');
    // Level 2 = Forager with its own emblem; next level hinted.
    expect(wrapper.text()).toContain('Forager');
    expect(wrapper.text()).toContain('Next up: Guard');
    expect(wrapper.text()).toContain('🍯');
  });

  it('highlights the current Infinia Level step in the ladder', async () => {
    const wrapper = await mountCenter();
    const steps = wrapper.findAll('ol li');
    expect(steps.length).toBe(5);
    const current = steps[2];
    expect(current.attributes('aria-current')).toBe('step');
    expect(current.classes()).toContain('border-accent');
    expect(steps[4].classes()).toContain('opacity-50');
  });

  it('summarizes the library and organizations', async () => {
    const wrapper = await mountCenter();
    expect(wrapper.text()).toContain('My library');
    expect(wrapper.text()).toContain('Markdown Tools');
    expect(wrapper.text()).toContain('Infinia Official');
  });

  it('edits the display name through the profile form', async () => {
    const wrapper = await mountCenter();
    const input = wrapper.find('input');
    expect((input.element as HTMLInputElement).value).toBe('Busy Bee');
    await input.setValue('Queen Bee');
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();
    const { api } = await import('../src/api/client');
    expect(api.put).toHaveBeenCalledWith('/api/v1/me', { displayName: 'Queen Bee' });
    expect(wrapper.text()).toContain('Display name updated');
  });

  it('shows role-aware quick links', async () => {
    const wrapper = await mountCenter();
    const links = wrapper.findAll('a').map((a) => a.attributes('href'));
    expect(links).toContain('/publisher');
    expect(links).not.toContain('/admin');
  });
});
