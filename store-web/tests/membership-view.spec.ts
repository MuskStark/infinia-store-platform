import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import MembershipView from '../src/views/MembershipView.vue';
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

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

async function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/membership');
  const wrapper = mount(MembershipView, {
    global: { plugins: [i18n, pinia, router] },
  });
  await flushPromises();
  return wrapper;
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
  wrapper?.unmount();
});
let wrapper: ReturnType<typeof mount> | null = null;

describe('MembershipView (会员等级购买)', () => {
  it('renders the ladder position and one buyable card per climbing plan', async () => {
    wrapper = await mountView();
    expect(wrapper.findAll('[data-testid="membership-plan"]').length).toBe(3);
    // The WORKER plan sits at the buyer's current level — not purchasable.
    const cards = wrapper.findAll('[data-testid="membership-plan"]');
    const worker = cards.find((c) => c.text().includes('Lv1'))!;
    const forager = cards.find((c) => c.text().includes('Lv2'))!;
    expect((worker.find('[data-testid="membership-buy"]').element as HTMLButtonElement).disabled)
      .toBe(true);
    expect(worker.text()).toContain(en.membership.owned);
    expect((forager.find('[data-testid="membership-buy"]').element as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it('creates an order and redirects the browser to the cashier URL', async () => {
    wrapper = await mountView();
    createMembershipOrder.mockResolvedValue({
      orderNo: 'MEM20260911ABCDEF',
      payUrl: 'https://pay.example/cashier',
      status: 'PENDING',
    });
    const buy = wrapper
      .findAll('[data-testid="membership-buy"]')
      .find((b) => !(b.element as HTMLButtonElement).disabled)!;
    await buy.trigger('click');
    await flushPromises();
    expect(createMembershipOrder).toHaveBeenCalledWith(
      expect.objectContaining({ planId: 'p-forager', channel: 'WECHAT' }),
    );
    expect(window.location.href).toBe('https://pay.example/cashier');
  });

  it('keeps the user on the page when no gateway is configured', async () => {
    const { api } = await import('../src/api/client');
    vi.mocked(api.getMembershipStatus).mockResolvedValueOnce({
      ...STATUS,
      channels: [],
    });
    wrapper = await mountView();
    expect(wrapper.find('[data-testid="membership-unconfigured"]').exists()).toBe(true);
    expect(
      (wrapper.find('[data-testid="membership-buy"]').element as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(window.location.href).toBe('');
  });
});
