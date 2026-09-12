import { act } from 'react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderInRouter, bodyText, resetStores } from './helpers';
import MembershipResultView from '../src/views/MembershipResultView';
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

function mountResult(orderNo = 'MEM20260911ABCDEF') {
  return renderInRouter(<MembershipResultView />, {
    route: `/store/membership/result?orderNo=${orderNo}`,
    path: '/store/membership/result',
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  getMembershipOrder.mockReset();
});
afterEach(() => {
  vi.useRealTimers();
  resetStores();
});

/** Advance the poll interval and settle the resulting async state updates. */
async function tick(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
}

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
    mountResult();
    await vi.waitFor(() => {
      expect(document.querySelector('[data-testid="membership-result-polling"]')).toBeTruthy();
    });

    await tick(2100);
    const paid = await vi.waitFor(() => {
      const el = document.querySelector('[data-testid="membership-result-paid"]');
      expect(el).toBeTruthy();
      return el!;
    });
    expect(paid.textContent).toContain(en.membership.result.success);
    // No further polling after a terminal state.
    const calls = getMembershipOrder.mock.calls.length;
    await tick(10_000);
    expect(getMembershipOrder.mock.calls.length).toBe(calls);
  });

  it('offers a re-order link when the payment window lapsed', async () => {
    getMembershipOrder.mockResolvedValue({
      orderNo: 'MEM20260911XYZ',
      status: 'CLOSED',
      priceFen: 600,
    });
    mountResult('MEM20260911XYZ');
    const closed = await vi.waitFor(() => {
      const el = document.querySelector('[data-testid="membership-result-closed"]');
      expect(el).toBeTruthy();
      return el!;
    });
    expect(closed.textContent).toContain(en.membership.result.closed);
    expect(closed.textContent).toContain(en.membership.result.reorder);
  });

  it('stops after the polling budget instead of hanging forever', async () => {
    getMembershipOrder.mockResolvedValue({ orderNo: 'X', status: 'PENDING' });
    mountResult('X');
    await vi.waitFor(() => {
      expect(getMembershipOrder.mock.calls.length).toBeGreaterThan(0);
    });
    await tick(61 * 2000);
    // Budget exhausted: the spinner stops and the state becomes an honest
    // "not confirmed yet" panel with a re-check action — never a fake verdict
    // and never an endless spinner (plan §6.8).
    const calls = getMembershipOrder.mock.calls.length;
    await tick(10_000);
    expect(getMembershipOrder.mock.calls.length).toBe(calls);
    expect(document.querySelector('[data-testid="membership-result-timeout"]')).toBeTruthy();
    expect(document.querySelector('[data-testid="membership-result-polling"]')).toBeFalsy();
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
    mountResult('');
    await vi.waitFor(() => {
      expect(document.querySelector('[data-testid="membership-result-polling"]')).toBeTruthy();
    });
    expect(bodyText()).toContain(en.membership.result.waitingNoOrder);

    await tick(2100);
    const paid = await vi.waitFor(() => {
      const el = document.querySelector('[data-testid="membership-result-paid"]');
      expect(el).toBeTruthy();
      return el!;
    });
    expect(paid.textContent).toContain(en.membership.result.success);
  });
});
