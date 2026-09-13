import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, asUser, resetStores, bodyText, allButtons } from './helpers';
import AdminView from '../src/views/AdminView';
import en from '../src/locales/en';

/**
 * Admin invitation console (管理 · 邀请码): the registration switch flips
 * through the API, codes are issued without limit, and the ledger lists every
 * code with issuer and redemption info.
 */

const CODES = [
  {
    codeId: 'c1',
    code: 'ADMINCODE1234',
    createdBy: 'u-admin',
    createdByEmail: 'admin@infinia.local',
    createdAt: '2026-09-10T00:00:00Z',
    usedBy: 'u2',
    usedByEmail: 'newbee@example.com',
    usedAt: '2026-09-11T00:00:00Z',
  },
  {
    codeId: 'c2',
    code: 'FRESHPRINT88',
    createdBy: 'u1',
    createdByEmail: 'bee@example.com',
    createdAt: '2026-09-12T00:00:00Z',
    usedBy: null,
    usedByEmail: null,
    usedAt: null,
  },
];

const setAdminRegistrationPolicy = vi.fn();
const createAdminInvitation = vi.fn();

vi.mock('../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/client')>()),
  api: {
    get: vi.fn(async (path: string) => {
      if (path.includes('/admin/reports')) return [];
      if (path.includes('/admin/audit-events')) return [];
      if (path === '/api/v1/admin/listings') return [];
      if (path === '/api/v1/admin/app-releases') return [];
      throw new Error('unexpected GET ' + path);
    }),
    getAdminUsers: vi.fn(async () => []),
    getRemoteDatabases: vi.fn(async () => []),
    getDataSourceStatus: vi.fn(async () => ({
      productName: null, productVersion: null, url: null,
      username: null, remoteOverrideActive: false, overrideName: null,
    })),
    getUpstreams: vi.fn(async () => []),
    getAdminMembershipPlans: vi.fn(async () => []),
    getAdminMembershipOrders: vi.fn(async () => []),
    getAdminInvitations: vi.fn(async () => CODES),
    getAdminRegistrationPolicy: vi.fn(async () => ({
      invitationRequired: false,
    })),
    setAdminRegistrationPolicy: (required: boolean) =>
      setAdminRegistrationPolicy(required),
    createAdminInvitation: () => createAdminInvitation(),
  },
}));

async function mountInvitationsTab() {
  asUser();
  renderInRouter(<AdminView />, { route: '/store/admin' });
  const tab = await vi.waitFor(() => {
    const button = allButtons().find(
      (b) => b.getAttribute('role') === 'tab' && b.textContent === en.admin.invitations,
    );
    expect(button).toBeTruthy();
    return button!;
  });
  fireEvent.click(tab);
  await vi.waitFor(() => {
    expect(
      document.querySelector('[data-testid="registration-policy-card"]'),
    ).toBeTruthy();
  });
}

beforeEach(() => {
  setAdminRegistrationPolicy.mockReset();
  createAdminInvitation.mockReset();
});
afterEach(() => {
  resetStores();
});

describe('AdminView invitation console (管理 · 邀请码)', () => {
  it('sits in the users nav group and lists the code ledger', async () => {
    await mountInvitationsTab();
    const table = document.querySelector(
      '[data-testid="admin-invitations-table"]',
    )!;
    expect(table.textContent).toContain('ADMINCODE1234');
    expect(table.textContent).toContain('admin@infinia.local');
    expect(table.textContent).toContain('bee@example.com');
    expect(table.textContent).toContain(en.account.invitationUnused);
    expect(table.textContent).toContain(
      en.account.invitationUsedBy.replace('{email}', 'newbee@example.com'),
    );
  });

  it('shows the switch state and flips it through the API', async () => {
    setAdminRegistrationPolicy.mockResolvedValue({ invitationRequired: true });
    await mountInvitationsTab();
    expect(
      document.querySelector('[data-testid="registration-policy-state"]')!
        .textContent,
    ).toContain(en.admin.registrationOff);
    const toggle = document.querySelector(
      '[data-testid="registration-policy-toggle"]',
    ) as HTMLButtonElement;
    expect(toggle.textContent).toContain(en.admin.registrationTurnOn);
    fireEvent.click(toggle);
    await vi.waitFor(() => {
      expect(setAdminRegistrationPolicy).toHaveBeenCalledWith(true);
    });
  });

  it('issues unlimited codes and surfaces the last one', async () => {
    createAdminInvitation.mockResolvedValue({
      ...CODES[1],
      codeId: 'c3',
      code: 'JUSTMINTED777',
    });
    const { api } = await import('../src/api/client');
    vi.mocked(api.getAdminInvitations).mockResolvedValue([
      { ...CODES[1], codeId: 'c3', code: 'JUSTMINTED777' },
      ...CODES,
    ]);
    await mountInvitationsTab();
    fireEvent.click(
      document.querySelector('[data-testid="admin-issue-invitation"]')!,
    );
    await vi.waitFor(() => {
      expect(createAdminInvitation).toHaveBeenCalledTimes(1);
      expect(
        document.querySelector('[data-testid="last-issued-code"]')!.textContent,
      ).toContain('JUSTMINTED777');
      expect(bodyText()).toContain('JUSTMINTED777');
    });
  });
});
