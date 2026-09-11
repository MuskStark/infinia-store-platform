import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import MembershipResultView from '../src/views/MembershipResultView.vue';
import en from '../src/locales/en';

/**
 * The cashier return page (支付回跳): lands before the gateway callback may
 * have arrived, so it polls the order — PAID flips to success and refreshes
 * /me (the badge must catch up), CLOSED tells the buyer to re-order.
 */

const getMembershipOrder = vi.fn();

vi.mock('../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/client')>()),
  api: {
    getMembershipOrder: (orderNo: string) => getMembershipOrder(orderNo),
    getMembershipStatus: vi.fn(async () => ({
      baseBeeLevel: 1,
      effectiveBeeLevel: 1,
      membershipLevel: null,
      membershipExpiresAt: null,
      channels: ['BMAC'],
    })),
    get: vi.fn(async () => ({ userId: 'u1', effectiveBeeLevel: 3 })),
  },
}));

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

async function mountResult(orderNo = 'MEM20260911ABCDEF') {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push(`/membership/result?orderNo=${orderNo}`);
  const wrapper = mount(MembershipResultView, {
    global: { plugins: [i18n, pinia, router] },
  });
  await flushPromises();
  return wrapper;
}

let wrapper: ReturnType<typeof mount> | null = null;

beforeEach(() => {
  vi.useFakeTimers();
  getMembershipOrder.mockReset();
});
afterEach(() => {
  vi.useRealTimers();
  wrapper?.unmount();
});

describe('MembershipResultView (支付结果轮询)', () => {
  it('polls until the order flips to PAID, then shows success', async () => {
    getMembershipOrder.mockResolvedValueOnce({
      orderNo: 'MEM20260911ABCDEF',
      status: 'PENDING',
      priceFen: 3600,
    });
    getMembershipOrder.mockResolvedValue({
      orderNo: 'MEM20260911ABCDEF',
      status: 'PAID',
      targetLevel: 3,
      durationDays: 90,
      priceFen: 3600,
    });
    wrapper = await mountResult();
    expect(wrapper.find('[data-testid="membership-result-polling"]').exists()).toBe(true);

    vi.advanceTimersByTime(2100);
    await flushPromises();
    const paid = wrapper.find('[data-testid="membership-result-paid"]');
    expect(paid.exists()).toBe(true);
    expect(paid.text()).toContain(en.membership.result.success);
    // No further polling after a terminal state.
    const calls = getMembershipOrder.mock.calls.length;
    vi.advanceTimersByTime(10_000);
    await flushPromises();
    expect(getMembershipOrder.mock.calls.length).toBe(calls);
  });

  it('offers a re-order link when the payment window lapsed', async () => {
    getMembershipOrder.mockResolvedValue({
      orderNo: 'MEM20260911XYZ',
      status: 'CLOSED',
      priceFen: 600,
    });
    wrapper = await mountResult('MEM20260911XYZ');
    const closed = wrapper.find('[data-testid="membership-result-closed"]');
    expect(closed.exists()).toBe(true);
    expect(closed.text()).toContain(en.membership.result.closed);
    expect(closed.text()).toContain(en.membership.result.reorder);
  });

  it('stops after the polling budget instead of hanging forever', async () => {
    getMembershipOrder.mockResolvedValue({ orderNo: 'X', status: 'PENDING' });
    wrapper = await mountResult('X');
    vi.advanceTimersByTime(61 * 2000);
    await flushPromises();
    // Still on the polling card, but the interval is gone (budget exhausted).
    const calls = getMembershipOrder.mock.calls.length;
    vi.advanceTimersByTime(10_000);
    await flushPromises();
    expect(getMembershipOrder.mock.calls.length).toBe(calls);
    expect(wrapper.find('[data-testid="membership-result-polling"]').exists()).toBe(true);
  });

  it('polls membership status on a bare visit (Buy Me a Coffee has no return URL)', async () => {
    const { api } = await import('../src/api/client');
    // First polls: no membership yet; then the webhook lands.
    vi.mocked(api.getMembershipStatus)
      .mockResolvedValueOnce({
        baseBeeLevel: 1, effectiveBeeLevel: 1, membershipLevel: null,
        membershipExpiresAt: null, channels: ['BMAC'],
      })
      .mockResolvedValue({
        baseBeeLevel: 1, effectiveBeeLevel: 3, membershipLevel: 3,
        membershipExpiresAt: '2026-12-11T02:00:00Z', channels: ['BMAC'],
      });
    wrapper = await mountResult('');
    expect(wrapper.find('[data-testid="membership-result-polling"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(en.membership.result.waitingNoOrder);

    vi.advanceTimersByTime(2100);
    await flushPromises();
    const paid = wrapper.find('[data-testid="membership-result-paid"]');
    expect(paid.exists()).toBe(true);
    expect(paid.text()).toContain(en.membership.result.success);
  });
});
