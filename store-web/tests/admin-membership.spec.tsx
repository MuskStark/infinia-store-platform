import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, asUser, resetStores, bodyText, allButtons } from './helpers';
import AdminView from '../src/views/AdminView';
import en from '../src/locales/en';

/**
 * Admin membership console (管理 · 会员套餐): plans render with their terms,
 * going off-sale / on-sale is one click, the create row posts yuan→fen, and
 * the order stream lists what buyers did with buyer info attached.
 */

const PLANS = [
  { planId: 'plan-worker', beeLevel: 1, durationDays: 30, priceFen: 600, active: true, sort: 1,
    externalUrl: 'https://buymeacoffee.com/infinia/extras/worker-30d',
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z' },
  { planId: 'plan-queen', beeLevel: 4, durationDays: 365, priceFen: 6600, active: false, sort: 4,
    externalUrl: null,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z' },
];

const ORDERS = [
  { orderNo: 'MEM20260911AAA', userId: 'u1', email: 'buyer@example.com',
    displayName: 'Buyer', targetLevel: 2, durationDays: 30, priceFen: 1600,
    status: 'PAID', channel: 'WECHAT', gatewayTradeNo: 'XH1',
    createdAt: '2026-09-11T02:00:00Z', paidAt: '2026-09-11T02:01:00Z',
    expiresAt: '2026-09-11T02:30:00Z' },
];

const updateAdminMembershipPlan = vi.fn();
const createAdminMembershipPlan = vi.fn();

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
    post: vi.fn(async (path: string) => {
      throw new Error('unexpected POST ' + path);
    }),
    getAdminUsers: vi.fn(async () => []),
    getRemoteDatabases: vi.fn(async () => []),
    getDataSourceStatus: vi.fn(async () => ({
      productName: null, productVersion: null, url: null,
      username: null, remoteOverrideActive: false, overrideName: null,
    })),
    getUpstreams: vi.fn(async () => []),
    getAdminMembershipPlans: vi.fn(async () => PLANS),
    getAdminMembershipOrders: vi.fn(async () => ORDERS),
    updateAdminMembershipPlan: (id: string, body: unknown) =>
      updateAdminMembershipPlan(id, body),
    createAdminMembershipPlan: (body: unknown) => createAdminMembershipPlan(body),
  },
}));

async function mountMembershipTab() {
  asUser();
  const utils = renderInRouter(<AdminView />, { route: '/store/admin' });
  // The admin shell loads every section's data; wait for the nav to settle.
  const membershipTab = await vi.waitFor(() => {
    const tab = allButtons().find((b) => b.getAttribute('role') === 'tab'
      && b.textContent === en.admin.membership);
    expect(tab).toBeTruthy();
    return tab!;
  });
  fireEvent.click(membershipTab);
  await vi.waitFor(() => {
    expect(document.querySelector('[data-testid="membership-plans-table"]')).toBeTruthy();
  });
  return utils;
}

beforeEach(() => {
  updateAdminMembershipPlan.mockReset();
  createAdminMembershipPlan.mockReset();
});
afterEach(() => {
  resetStores();
});

describe('AdminView membership console (管理 · 会员套餐)', () => {
  it('lists plans and the recent order stream with buyer info', async () => {
    await mountMembershipTab();
    const table = document.querySelector('[data-testid="membership-plans-table"]')!;
    expect(table.textContent).toContain('Lv1');
    expect(table.textContent).toContain('Lv4');
    // ¥16.00 order rendered from integer fen.
    expect(bodyText()).toContain('buyer@example.com');
    expect(bodyText()).toContain('MEM20260911AAA');
    expect(bodyText()).toContain('¥16');
  });

  it('toggles a plan off-sale with one click and updates in place', async () => {
    updateAdminMembershipPlan.mockImplementation(async (id: string, body: Record<string, unknown>) =>
      ({ ...PLANS.find((p) => p.planId === id), ...body }));
    await mountMembershipTab();
    const delist = allButtons().filter((b) => b.textContent === en.admin.delist);
    expect(delist.length).toBe(1); // only the active plan shows "delist"
    fireEvent.click(delist[0]);
    await vi.waitFor(() => {
      expect(updateAdminMembershipPlan).toHaveBeenCalledWith(
        'plan-worker',
        expect.objectContaining({ active: false, priceFen: 600, durationDays: 30 }),
      );
    });
  });

  it('creates a plan from the form, converting yuan to fen', async () => {
    createAdminMembershipPlan.mockResolvedValue(PLANS[0]);
    await mountMembershipTab();
    const form = document.querySelector('form')!;
    // Default draft: level WORKER, 30 days, ¥6, no external link.
    fireEvent.submit(form);
    await vi.waitFor(() => {
      expect(createAdminMembershipPlan).toHaveBeenCalledWith(
        expect.objectContaining({ beeLevel: 1, durationDays: 30, priceFen: 600,
          externalUrl: '' }),
      );
    });
  });

  it('edits a plan external checkout link in place', async () => {
    updateAdminMembershipPlan.mockImplementation(async (id: string, body: Record<string, unknown>) =>
      ({ ...PLANS.find((p) => p.planId === id), ...body }));
    await mountMembershipTab();
    const urlInput = document.querySelector('input[id^="url-"]') as HTMLInputElement;
    expect(urlInput.value)
      .toBe('https://buymeacoffee.com/infinia/extras/worker-30d');
    fireEvent.change(urlInput, { target: { value: 'https://buymeacoffee.com/infinia/extras/worker-90d' } });
    await vi.waitFor(() => {
      expect(updateAdminMembershipPlan).toHaveBeenCalledWith(
        'plan-worker',
        expect.objectContaining({ externalUrl: 'https://buymeacoffee.com/infinia/extras/worker-90d',
          priceFen: 600 }),
      );
    });
  });
});
