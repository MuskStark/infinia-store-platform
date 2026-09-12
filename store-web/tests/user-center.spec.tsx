import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, asUser, resetStores, bodyText, allButtons } from './helpers';
import type { MembershipStatus } from '../src/api/client';
import AccountView from '../src/views/AccountView';
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
        case '/api/v1/me/library':
          return {
            favorites: [],
            entitlements: [
              { listingCoordinate: 'a', free: true, acquiredAt: 'x' },
              { listingCoordinate: 'b', free: true, acquiredAt: 'y' },
              { listingCoordinate: 'c', free: true, acquiredAt: 'z' },
            ],
            installHistory: [],
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

/** Same mock instance `../src/api/client` hands to the component. */
async function mockedApi() {
  const { api } = await import('../src/api/client');
  return vi.mocked(api);
}

async function mountedCenter() {
  asUser();
  const utils = renderInRouter(<AccountView />, { route: '/store/account' });
  await vi.waitFor(() => {
    expect(bodyText()).toContain('Busy Bee');
  });
  return utils;
}

beforeEach(() => {
  resetStores();
});
afterEach(() => {
  resetStores();
});

describe('User Center (用户中心)', () => {
  it('renders identity with role badges and the single level line', async () => {
    await mountedCenter();
    expect(bodyText()).toContain('User Center');
    expect(bodyText()).toContain('Busy Bee');
    expect(bodyText()).toContain('bee@example.com');
    // Roles ride next to the name; the level line is crest + name + number.
    const line = document.querySelector('[data-testid="account-level-line"]')!;
    expect(line.textContent).toContain('Forager');
    expect(line.textContent).toContain('Lv2');
    expect(line.textContent).toContain('Next up: Guard');
    // No ladder, no duplicated membership statement.
    expect(document.querySelector('ol')).toBeNull();
    // Holdings at a glance: artifacts owned and signed-in devices.
    expect(document.querySelector('[data-testid="account-artifact-count"]')!.textContent!)
      .toMatch(/Artifacts\s*3/);
    expect(document.querySelector('[data-testid="account-device-count"]')!.textContent!)
      .toMatch(/Devices\s*1/);
  });

  it('offers the upgrade action inline for a buyer without a membership', async () => {
    await mountedCenter();
    const cta = document.querySelector('[data-testid="account-membership-cta"]') as HTMLAnchorElement;
    expect(cta.getAttribute('href')).toBe('/store/membership');
    expect(cta.textContent).toBe(en.account.membershipCta);
  });

  it('shows the membership deadline inline with a renew action', async () => {
    const api = await mockedApi();
    api.getMembershipStatus.mockResolvedValueOnce({
      ...MEMBERSHIP_STATUS,
      effectiveBeeLevel: 3,
      membershipLevel: 3,
      membershipExpiresAt: '2026-12-11T00:00:00Z',
    });
    await mountedCenter();
    const line = document.querySelector('[data-testid="account-level-line"]')!;
    expect(line.textContent).toContain('Guard');
    expect(document.querySelector('[data-testid="account-membership-active"]')!.textContent!)
      .toContain('member until');
    expect(document.querySelector('[data-testid="account-membership-cta"]')!.textContent!)
      .toBe(en.membership.renew);
  });

  it('does not mirror navigation-owned surfaces (library, organizations)', async () => {
    const api = await mockedApi();
    await mountedCenter();
    expect(bodyText()).not.toContain('My library');
    expect(bodyText()).not.toContain('My organizations');
    // The view never even asks for organizations.
    expect(api.get).not.toHaveBeenCalledWith('/api/v1/organizations');
  });

  it('updates the display name from the account card', async () => {
    const api = await mockedApi();
    await mountedCenter();
    const input = document.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('Busy Bee');
    fireEvent.change(input, { target: { value: 'Queen Bee' } });
    const form = document.querySelector('form')!;
    fireEvent.submit(form);
    await vi.waitFor(() => {
      expect(api.put).toHaveBeenCalledWith('/api/v1/me', { displayName: 'Queen Bee' });
    });
    expect(bodyText()).toContain('Display name updated');
  });

  it('changes the password from the same card', async () => {
    const api = await mockedApi();
    await mountedCenter();
    const forms = document.querySelectorAll('form');
    expect(forms.length).toBe(2);
    const passwordForm = forms[1];
    const inputs = passwordForm.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'Password123!' } });
    fireEvent.change(inputs[1], { target: { value: 'NewPassword456!' } });
    fireEvent.submit(passwordForm);
    await vi.waitFor(() => {
      expect(api.put).toHaveBeenCalledWith('/api/v1/me/password', {
        currentPassword: 'Password123!',
        newPassword: 'NewPassword456!',
      });
      // The success banner is the user-visible outcome of that same update.
      expect(bodyText()).toContain('Password changed');
    });
  });

  it('lists sign-in sessions and devices with revoke actions', async () => {
    const api = await mockedApi();
    await mountedCenter();
    expect(bodyText()).toContain(en.account.signinDevices);
    expect(bodyText()).toContain('Active sessions (1)');
    expect(bodyText()).toContain('Devices (1)');
    const deviceButton = allButtons().find((b) => b.textContent === en.account.revoke);
    deviceButton!.click();
    await vi.waitFor(() => {
      expect(api.delete).toHaveBeenCalledWith('/api/v1/me/sessions/s1');
    });
  });
});
