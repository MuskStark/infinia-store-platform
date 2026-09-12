import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderInRouter, bodyText, allButtons, resetStores } from './helpers';
import MembershipView from '../src/views/MembershipView';
import en from '../src/locales/en';

/**
 * Membership purchase page (会员等级购买): the buyer sees their effective
 * level, one card per plan, and can only buy plans that climb the ladder or
 * renew the active tier. Buying creates an order and leaves for the cashier
 * URL the backend returned.
 */

const STATUS = {
  baseBeeLevel: 1,
  effectiveBeeLevel: 1,
  membershipLevel: null,
  membershipExpiresAt: null,
  channels: ['WECHAT', 'ALIPAY'],
};

const PLANS = [
  { planId: 'p-worker', beeLevel: 1, durationDays: 30, priceFen: 600, active: true, sort: 1 },
  { planId: 'p-forager', beeLevel: 2, durationDays: 30, priceFen: 1600, active: true, sort: 2 },
  { planId: 'p-queen', beeLevel: 4, durationDays: 365, priceFen: 6600, active: true, sort: 4 },
];

const createMembershipOrder = vi.fn();

vi.mock('../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/client')>()),
  api: {
    getMembershipStatus: vi.fn(async () => STATUS),
    getMembershipPlans: vi.fn(async () => PLANS),
    createMembershipOrder: (body: unknown) => createMembershipOrder(body),
  },
}));

function mountView() {
  return renderInRouter(<MembershipView />, { route: '/store/membership' });
}

function planCards() {
  return Array.from(document.querySelectorAll('[data-testid="membership-plan"]'));
}

/** jsdom refuses location.href navigation; swap in a recording stand-in. */
const originalLocation = window.location;

beforeEach(() => {
  createMembershipOrder.mockReset();
  Object.defineProperty(window, 'location', {
    value: { href: '' } as unknown as Location,
    writable: true,
  });
});
afterEach(() => {
  Object.defineProperty(window, 'location', { value: originalLocation, writable: true });
  resetStores();
});

describe('MembershipView (会员等级购买)', () => {
  it('renders the ladder position and one buyable card per climbing plan', async () => {
    mountView();
    const cards = await vi.waitFor(() => {
      const found = planCards();
      expect(found.length).toBe(3);
      return found;
    });
    // The WORKER plan sits at the buyer's current level — not purchasable.
    const worker = cards.find((c) => c.textContent!.includes('Lv1'))!;
    const forager = cards.find((c) => c.textContent!.includes('Lv2'))!;
    expect((worker.querySelector('[data-testid="membership-buy"]') as HTMLButtonElement).disabled)
      .toBe(true);
    expect(worker.textContent).toContain(en.membership.owned);
    expect((forager.querySelector('[data-testid="membership-buy"]') as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it('creates an order and redirects the browser to the cashier URL', async () => {
    createMembershipOrder.mockResolvedValue({
      orderNo: 'MEM20260911ABCDEF',
      payUrl: 'https://pay.example/cashier',
      status: 'PENDING',
    });
    mountView();
    const buy = await vi.waitFor(() => {
      const found = allButtons().find(
        (b) => b.dataset.testid === 'membership-buy' && !b.disabled,
      );
      expect(found).toBeTruthy();
      return found!;
    });
    buy.click();
    await vi.waitFor(() => {
      expect(createMembershipOrder).toHaveBeenCalledWith(
        expect.objectContaining({ planId: 'p-forager', channel: 'WECHAT' }),
      );
    });
    expect(window.location.href).toBe('https://pay.example/cashier');
  });

  it('keeps the user on the page when no gateway is configured', async () => {
    const { api } = await import('../src/api/client');
    vi.mocked(api.getMembershipStatus).mockResolvedValueOnce({
      ...STATUS,
      channels: [],
    });
    mountView();
    await vi.waitFor(() => {
      expect(document.querySelector('[data-testid="membership-unconfigured"]')).toBeTruthy();
    });
    expect(
      (document.querySelector('[data-testid="membership-buy"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(window.location.href).toBe('');
  });

  it('shows the Buy Me a Coffee email-matching hint when it is the channel', async () => {
    const { api } = await import('../src/api/client');
    vi.mocked(api.getMembershipStatus).mockResolvedValueOnce({
      ...STATUS,
      channels: ['BMAC'],
    });
    mountView();
    await vi.waitFor(() => {
      expect(document.querySelector('[data-testid="membership-bmac-hint"]')).toBeTruthy();
    });
    expect(bodyText()).toContain(en.membership.channel.BMAC);
    // Single-channel gateways hide the picker entirely.
    expect(bodyText()).not.toContain(en.membership.payWith);
  });
});
