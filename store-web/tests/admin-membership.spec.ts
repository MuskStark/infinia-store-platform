import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import AdminView from '../src/views/AdminView.vue';
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

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>',
};

async function mountMembershipTab() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/admin');
  const wrapper = mount(AdminView, {
    global: { plugins: [i18n, pinia, router], stubs: { RouterLink: RouterLinkStub } },
  });
  await flushPromises();
  const tabs = wrapper.findAll('[role="tab"]');
  const membershipTab = tabs.find((tabButton) => tabButton.text() === en.admin.membership);
  await membershipTab!.trigger('click');
  await flushPromises();
  return wrapper;
}

let wrapper: ReturnType<typeof mount> | null = null;
beforeEach(() => {
  updateAdminMembershipPlan.mockReset();
  createAdminMembershipPlan.mockReset();
});
afterEach(() => {
  wrapper?.unmount();
});

describe('AdminView membership console (管理 · 会员套餐)', () => {
  it('lists plans and the recent order stream with buyer info', async () => {
    wrapper = await mountMembershipTab();
    const table = wrapper.find('[data-testid="membership-plans-table"]');
    expect(table.exists()).toBe(true);
    expect(table.text()).toContain('Lv1');
    expect(table.text()).toContain('Lv4');
    // ¥16.00 order rendered from integer fen.
    expect(wrapper.text()).toContain('buyer@example.com');
    expect(wrapper.text()).toContain('MEM20260911AAA');
    expect(wrapper.text()).toContain('¥16');
  });

  it('toggles a plan off-sale with one click and updates in place', async () => {
    updateAdminMembershipPlan.mockImplementation(async (id: string, body: Record<string, unknown>) =>
      ({ ...PLANS.find((p) => p.planId === id), ...body }));
    wrapper = await mountMembershipTab();
    const delist = wrapper.findAll('button')
      .filter((b) => b.text() === en.admin.delist);
    expect(delist.length).toBe(1); // only the active plan shows "delist"
    await delist[0].trigger('click');
    await flushPromises();
    expect(updateAdminMembershipPlan).toHaveBeenCalledWith(
      'plan-worker',
      expect.objectContaining({ active: false, priceFen: 600, durationDays: 30 }),
    );
  });

  it('creates a plan from the form, converting yuan to fen', async () => {
    createAdminMembershipPlan.mockResolvedValue(PLANS[0]);
    wrapper = await mountMembershipTab();
    const form = wrapper.find('form');
    // Default draft: level WORKER, 30 days, ¥6, no external link.
    await form.trigger('submit');
    await flushPromises();
    expect(createAdminMembershipPlan).toHaveBeenCalledWith(
      expect.objectContaining({ beeLevel: 1, durationDays: 30, priceFen: 600,
        externalUrl: '' }),
    );
  });

  it('edits a plan external checkout link in place', async () => {
    updateAdminMembershipPlan.mockImplementation(async (id: string, body: Record<string, unknown>) =>
      ({ ...PLANS.find((p) => p.planId === id), ...body }));
    wrapper = await mountMembershipTab();
    const urlInput = wrapper.find('input[id^="url-"]');
    expect((urlInput.element as HTMLInputElement).value)
      .toBe('https://buymeacoffee.com/infinia/extras/worker-30d');
    await urlInput.setValue('https://buymeacoffee.com/infinia/extras/worker-90d');
    await urlInput.trigger('change');
    await flushPromises();
    expect(updateAdminMembershipPlan).toHaveBeenCalledWith(
      'plan-worker',
      expect.objectContaining({ externalUrl: 'https://buymeacoffee.com/infinia/extras/worker-90d',
        priceFen: 600 }),
    );
  });
});
