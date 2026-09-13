import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, asUser, resetStores, bodyText } from './helpers';
import type { MyInvitations } from '../src/api/client';
import AccountView from '../src/views/AccountView';
import en from '../src/locales/en';

/**
 * Invitation sharing on the User Center (我的 · 邀请码): the card states the
 * monthly quota, lists issued codes with their redemption state, generates new
 * codes within quota, and explains the Lv2 gate for accounts below it.
 */

const UNUSED_CODE: MyInvitations['invitations'][number] = {
  codeId: 'c1',
  code: '7QK2WBXNM4HT',
  createdBy: 'u1',
  createdByEmail: 'bee@example.com',
  createdAt: '2026-09-01T00:00:00Z',
  usedBy: null,
  usedByEmail: null,
  usedAt: null,
};

const USED_CODE: MyInvitations['invitations'][number] = {
  codeId: 'c2',
  code: '3NM4HT7QK2WBX',
  createdBy: 'u1',
  createdByEmail: 'bee@example.com',
  createdAt: '2026-09-02T00:00:00Z',
  usedBy: 'u9',
  usedByEmail: 'friend@example.com',
  usedAt: '2026-09-03T00:00:00Z',
};

const createInvitation = vi.fn();
const getMyInvitations = vi.fn();

vi.mock('../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/client')>()),
  api: {
    get: vi.fn(async (path: string) => {
      switch (path) {
        case '/api/v1/me':
          return {
            userId: 'u1',
            email: 'bee@example.com',
            displayName: 'Busy Bee',
            roles: ['USER'],
            beeLevel: 2,
            effectiveBeeLevel: 2,
            createdAt: '2026-08-01T00:00:00Z',
          };
        case '/api/v1/me/library':
          return { favorites: [], entitlements: [], installHistory: [] };
        case '/api/v1/me/sessions':
          return [];
        case '/api/v1/me/devices':
          return [];
        default:
          return [];
      }
    }),
    getMembershipStatus: vi.fn(async () => ({
      baseBeeLevel: 2,
      effectiveBeeLevel: 2,
      membershipLevel: null,
      membershipExpiresAt: null,
      channels: [],
    })),
    getMyInvitations: () => getMyInvitations(),
    createInvitation: () => createInvitation(),
    put: vi.fn(async () => undefined),
    delete: vi.fn(async () => undefined),
  },
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
}));

async function mountedCenter(invitations: MyInvitations) {
  getMyInvitations.mockResolvedValue(invitations);
  asUser();
  renderInRouter(<AccountView />, { route: '/store/account' });
  await vi.waitFor(() => {
    expect(
      document.querySelector('[data-testid="account-invitations"]'),
    ).toBeTruthy();
  });
}

beforeEach(() => {
  createInvitation.mockReset();
  getMyInvitations.mockReset();
});
afterEach(() => {
  resetStores();
});

describe('User Center invitations card (我的 · 邀请码)', () => {
  it('states the monthly quota and lists codes with redemption state', async () => {
    await mountedCenter({
      unlimited: false,
      monthlyLimit: 2,
      issuedThisMonth: 2,
      invitations: [UNUSED_CODE, USED_CODE],
    });
    const card = document.querySelector('[data-testid="account-invitations"]')!;
    expect(card.textContent).toContain(en.account.invitations);
    expect(
      document.querySelector('[data-testid="account-invitation-quota"]')!
        .textContent,
    ).toContain('2 / 2');
    expect(card.textContent).toContain('7QK2WBXNM4HT');
    expect(card.textContent).toContain(en.account.invitationUnused);
    expect(card.textContent).toContain(
      en.account.invitationUsedBy.replace('{email}', 'friend@example.com'),
    );
  });

  it('exhausts the quota: the generate button is disabled, not hidden', async () => {
    await mountedCenter({
      unlimited: false,
      monthlyLimit: 2,
      issuedThisMonth: 2,
      invitations: [UNUSED_CODE, USED_CODE],
    });
    const button = document.querySelector(
      '[data-testid="account-issue-invitation"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain(en.account.invitationQuotaEmpty);
  });

  it('generates a code within quota and refreshes the list', async () => {
    createInvitation.mockResolvedValue({ ...UNUSED_CODE });
    getMyInvitations.mockResolvedValueOnce({
      unlimited: false,
      monthlyLimit: 2,
      issuedThisMonth: 0,
      invitations: [],
    });
    await mountedCenter({
      unlimited: false,
      monthlyLimit: 2,
      issuedThisMonth: 1,
      invitations: [UNUSED_CODE],
    });
    const refreshed: MyInvitations = {
      unlimited: false,
      monthlyLimit: 2,
      issuedThisMonth: 2,
      invitations: [UNUSED_CODE, USED_CODE],
    };
    getMyInvitations.mockResolvedValueOnce(refreshed);
    fireEvent.click(
      document.querySelector('[data-testid="account-issue-invitation"]')!,
    );
    await vi.waitFor(() => {
      expect(createInvitation).toHaveBeenCalledTimes(1);
      expect(getMyInvitations).toHaveBeenCalledTimes(2);
      expect(bodyText()).toContain('3NM4HT7QK2WBX');
    });
  });

  it('shows the level gate instead of the share controls below Lv2', async () => {
    await mountedCenter({
      unlimited: false,
      monthlyLimit: 0,
      issuedThisMonth: 0,
      invitations: [],
    });
    const card = document.querySelector('[data-testid="account-invitations"]')!;
    expect(card.textContent).toContain(en.account.invitationsLocked);
    expect(
      document.querySelector('[data-testid="account-issue-invitation"]'),
    ).toBeNull();
  });

  it('renders unlimited sharing for platform admins', async () => {
    await mountedCenter({
      unlimited: true,
      monthlyLimit: 0,
      issuedThisMonth: 40,
      invitations: [UNUSED_CODE],
    });
    const card = document.querySelector('[data-testid="account-invitations"]')!;
    expect(card.textContent).toContain(en.account.invitationsUnlimited);
    expect(
      (document.querySelector('[data-testid="account-issue-invitation"]') as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });
});
