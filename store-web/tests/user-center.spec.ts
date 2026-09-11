import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import { useAuthStore } from '../src/stores/auth';
import type { MembershipStatus } from '../src/api/client';
import AccountView from '../src/views/AccountView.vue';
import en from '../src/locales/en';

/**
 * User Center (用户中心) as pure account management: the level line carries
 * the membership deadline and its action inline (never a second level
 * statement), navigation-owned surfaces (library, organizations) are NOT
 * mirrored here, and credentials (name + password) plus sign-in footprint
 * complete the page.
 */

const MEMBERSHIP_STATUS: MembershipStatus = {
  baseBeeLevel: 2,
  effectiveBeeLevel: 2,
  membershipLevel: null,
  membershipExpiresAt: null,
  channels: ['BMAC'],
};

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
            effectiveBeeLevel: 2,
            createdAt: '2026-08-01T00:00:00Z',
          };
        case '/api/v1/me/sessions':
          return [{ sessionId: 's1', clientId: 'store-web', kind: 'PASSWORD', createdAt: 't' }];
        case '/api/v1/me/devices':
          return [
            { deviceId: 'd1', name: 'Laptop', platform: 'macos', revoked: false },
          ];
        default:
          return [];
      }
    }),
    getMembershipStatus: vi.fn(async () => MEMBERSHIP_STATUS),
    put: vi.fn(async () => undefined),
    delete: vi.fn(async () => undefined),
  },
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
}));

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

async function mountCenter() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.user = {
    userId: 'u1',
    email: 'bee@example.com',
    displayName: 'Busy Bee',
    roles: ['USER', 'PUBLISHER'],
    beeLevel: 2,
    effectiveBeeLevel: 2,
    createdAt: '2026-08-01T00:00:00Z',
  } as never;
  const wrapper = mount(AccountView, {
    global: {
      plugins: [i18n, pinia],
      stubs: {
        RouterLink: {
          name: 'RouterLink',
          props: ['to'],
          template: '<a :href="to"><slot /></a>',
        },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

let wrapper: ReturnType<typeof mount> | null = null;
beforeEach(() => {
  wrapper = null;
});
afterEach(() => {
  wrapper?.unmount();
});

describe('User Center (用户中心)', () => {
  it('renders identity with role badges and the single level line', async () => {
    wrapper = await mountCenter();
    expect(wrapper.text()).toContain('User Center');
    expect(wrapper.text()).toContain('Busy Bee');
    expect(wrapper.text()).toContain('bee@example.com');
    // Roles ride next to the name; the level line is crest + name + number.
    const line = wrapper.find('[data-testid="account-level-line"]');
    expect(line.text()).toContain('Forager');
    expect(line.text()).toContain('Lv2');
    expect(line.text()).toContain('Next up: Guard');
    // No ladder, no duplicated membership statement.
    expect(wrapper.find('ol').exists()).toBe(false);
  });

  it('offers the upgrade action inline for a buyer without a membership', async () => {
    wrapper = await mountCenter();
    const cta = wrapper.find('[data-testid="account-membership-cta"]');
    expect(cta.attributes('href')).toBe('/membership');
    expect(cta.text()).toBe(en.account.membershipCta);
  });

  it('shows the membership deadline inline with a renew action', async () => {
    const { api } = await import('../src/api/client');
    vi.mocked(api.getMembershipStatus).mockResolvedValueOnce({
      ...MEMBERSHIP_STATUS,
      effectiveBeeLevel: 3,
      membershipLevel: 3,
      membershipExpiresAt: '2026-12-11T00:00:00Z',
    });
    wrapper = await mountCenter();
    const line = wrapper.find('[data-testid="account-level-line"]');
    expect(line.text()).toContain('Guard');
    expect(wrapper.find('[data-testid="account-membership-active"]').text())
      .toContain('member until');
    expect(wrapper.find('[data-testid="account-membership-cta"]').text())
      .toBe(en.membership.renew);
  });

  it('does not mirror navigation-owned surfaces (library, organizations)', async () => {
    wrapper = await mountCenter();
    const { api } = await import('../src/api/client');
    expect(wrapper.text()).not.toContain('My library');
    expect(wrapper.text()).not.toContain('My organizations');
    // The view never even asks for them.
    expect(vi.mocked(api.get)).not.toHaveBeenCalledWith('/api/v1/me/library');
    expect(vi.mocked(api.get)).not.toHaveBeenCalledWith('/api/v1/organizations');
  });

  it('updates the display name from the account card', async () => {
    wrapper = await mountCenter();
    const input = wrapper.find('input');
    expect((input.element as HTMLInputElement).value).toBe('Busy Bee');
    await input.setValue('Queen Bee');
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();
    const { api } = await import('../src/api/client');
    expect(api.put).toHaveBeenCalledWith('/api/v1/me', { displayName: 'Queen Bee' });
    expect(wrapper.text()).toContain('Display name updated');
  });

  it('changes the password from the same card', async () => {
    wrapper = await mountCenter();
    const forms = wrapper.findAll('form');
    expect(forms.length).toBe(2);
    const passwordForm = forms[1];
    const inputs = passwordForm.findAll('input');
    await inputs[0].setValue('Password123!');
    await inputs[1].setValue('NewPassword456!');
    await passwordForm.trigger('submit.prevent');
    await flushPromises();
    const { api } = await import('../src/api/client');
    expect(api.put).toHaveBeenCalledWith('/api/v1/me/password', {
      currentPassword: 'Password123!',
      newPassword: 'NewPassword456!',
    });
    expect(wrapper.text()).toContain('Password changed');
  });

  it('lists sign-in sessions and devices with revoke actions', async () => {
    wrapper = await mountCenter();
    const text = wrapper.text();
    expect(text).toContain(en.account.signinDevices);
    expect(text).toContain('Active sessions (1)');
    expect(text).toContain('Devices (1)');
    const deviceButton = wrapper
      .findAll('button')
      .find((b) => b.text() === en.account.revoke);
    await deviceButton!.trigger('click');
    await flushPromises();
    const { api } = await import('../src/api/client');
    expect(api.delete).toHaveBeenCalledWith('/api/v1/me/sessions/s1');
  });
});
