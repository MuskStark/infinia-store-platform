import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import AdminView from '../src/views/AdminView.vue';
import en from '../src/locales/en';

/**
 * Upstream sync UX (goal: 添加上游后直接显示同步状态，报错进日志弹窗).
 * Registration runs the first sync in the background, so every card carries a
 * status line — Syncing… → Sync OK / Sync failed — and per-run detail lives
 * behind the View log dialog instead of an inline wall of error text.
 */

const SYNCING = {
  upstreamId: '00000000-0000-7000-8000-000000000001',
  name: 'SkillHub (WorkBuddy)',
  marketplaceUrl: 'https://api.skillhub.cn/api/skills?pages=1',
  targetNamespace: 'skillhub',
  adapterType: 'SKILLHUB_REGISTRY',
  enabled: true,
  lastSyncAt: null,
  lastSyncOk: null,
  lastError: null,
  syncStatus: 'SYNCING',
  lastRunStartedAt: '2026-09-10T02:00:00Z',
  lastRunImported: null,
  lastRunSkipped: null,
  lastRunFailed: null,
};

const FAILED = {
  upstreamId: '00000000-0000-7000-8000-000000000002',
  name: 'Broken mirror',
  marketplaceUrl: 'https://down.example.com/marketplace.json',
  targetNamespace: 'broken',
  adapterType: 'AUTO',
  enabled: true,
  lastSyncAt: '2026-09-10T01:00:00Z',
  lastSyncOk: false,
  // Long, ugly, pre-existing blob: must stay OUT of the card, only in the log.
  lastError: 'headhunter-pro: Upstream payload for skillhub-headhunter-pro was '
    + 'blocked by security scan [secret.generic-assignment]; wechat-channels-cover-maker: '
    + 'GET https://api.skillhub.cn/api/v1/skills/wechat-channels-cover-maker failed with 502',
  syncStatus: 'FAILED',
  lastRunStartedAt: '2026-09-10T01:00:00Z',
  lastRunImported: 3,
  lastRunSkipped: 11,
  lastRunFailed: 2,
};

const RUNS = [
  {
    runId: '00000000-0000-7000-8000-00000000000a',
    startedAt: '2026-09-10T01:00:00Z',
    finishedAt: '2026-09-10T01:02:00Z',
    imported: 3,
    skipped: 11,
    failed: 2,
    status: 'PARTIAL',
    errors: [
      'headhunter-pro: Upstream payload for skillhub-headhunter-pro was blocked by security scan [secret.generic-assignment]',
      'wechat-channels-cover-maker: GET https://api.skillhub.cn/api/v1/skills/wechat-channels-cover-maker failed with 502',
    ],
  },
];

const OK = {
  upstreamId: '00000000-0000-7000-8000-000000000003',
  name: 'Claude official',
  marketplaceUrl: 'https://github.com/obra/superpowers',
  targetNamespace: 'claude',
  adapterType: 'CLAUDE_MARKETPLACE',
  enabled: true,
  lastSyncAt: '2026-09-09T22:00:00Z',
  lastSyncOk: true,
  lastError: null,
  syncStatus: 'OK',
  lastRunStartedAt: '2026-09-09T22:00:00Z',
  lastRunImported: 12,
  lastRunSkipped: 30,
  lastRunFailed: 0,
};

const FIXTURES = [SYNCING, FAILED, OK];
const getUpstreams = vi.fn(async (): Promise<typeof FIXTURES> => FIXTURES);
const getUpstreamSyncRuns = vi.fn(async (_id: string): Promise<typeof RUNS> => RUNS);

vi.mock('../src/api/client', () => ({
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
    getUpstreams: () => getUpstreams(),
    getUpstreamSyncRuns: (id: string) => getUpstreamSyncRuns(id),
  },
  ApiRequestError: class extends Error {},
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
}));

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>',
};

async function mountAdmin() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/admin');
  const wrapper = mount(AdminView, {
    global: {
      plugins: [i18n, pinia, router],
      stubs: { RouterLink: RouterLinkStub },
    },
  });
  await flushPromises();
  await wrapper.find('[role="tab"]').element; // settle initial render
  const tabs = wrapper.findAll('[role="tab"]');
  const upstreamsTab = tabs.find((tabButton) =>
    tabButton.text() === en.admin.upstreams);
  await upstreamsTab!.trigger('click');
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  getUpstreams.mockReset();
  getUpstreams.mockResolvedValue([SYNCING, FAILED, OK]);
  getUpstreamSyncRuns.mockReset();
  getUpstreamSyncRuns.mockResolvedValue(RUNS);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('AdminView upstream sync status (添加上游同步状态)', () => {
  it('renders the syncing state and disables sync-now for an open run', async () => {
    const wrapper = await mountAdmin();
    const text = wrapper.text();
    expect(text).toContain(en.admin.syncSyncing);
    expect(text).toContain(en.admin.syncOk);
    expect(text).toContain(en.admin.syncFailed);
    // The failed card keeps raw errors off the list — they live in the log.
    expect(text).not.toContain('secret.generic-assignment');
    const syncButtons = wrapper.findAll('button')
      .filter((b) => (b.element as HTMLButtonElement).textContent?.trim() === en.admin.syncNow);
    // One button per row; only the SYNCING row's is disabled.
    expect(syncButtons.length).toBe(3);
    expect(syncButtons.filter((b) => (b.element as HTMLButtonElement).disabled).length).toBe(1);
    wrapper.unmount();
  });

  it('shows per-run detail in the log dialog, never inline on the card', async () => {
    const wrapper = await mountAdmin();
    const logButtons = wrapper.findAll('button')
      .filter((b) => b.text() === en.admin.viewLog);
    expect(logButtons.length).toBe(3);
    // Open the log of the failed source (second card).
    await logButtons[1].trigger('click');
    await flushPromises();

    const dialog = wrapper.find('[role="dialog"]');
    expect(dialog.exists()).toBe(true);
    expect(dialog.text()).toContain(en.admin.syncLogTitle.replace('{name}', FAILED.name));
    expect(dialog.text()).toContain('secret.generic-assignment');
    expect(dialog.text()).toContain('failed with 502');

    await dialog.findAll('button')
      .find((b) => b.text() === en.common.close)!.trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('polls while a run is open and stops once every run settled', async () => {
    vi.useFakeTimers();
    // Mount with fake timers already active so the component's interval uses them.
    const wrapper = await mountAdmin();
    const callsAfterMount = getUpstreams.mock.calls.length;
    expect(callsAfterMount).toBeGreaterThan(0);

    vi.advanceTimersByTime(4100);
    await flushPromises();
    expect(getUpstreams.mock.calls.length).toBeGreaterThan(callsAfterMount);

    // The next poll returns settled rows → polling stops by itself.
    getUpstreams.mockResolvedValue([{
      ...SYNCING,
      lastSyncAt: '2026-09-10T03:00:00Z',
      lastSyncOk: true,
      lastError: null,
      syncStatus: 'OK',
      lastRunImported: 1,
      lastRunSkipped: 0,
      lastRunFailed: 0,
    }]);
    vi.advanceTimersByTime(4100);
    await flushPromises();
    const settledCalls = getUpstreams.mock.calls.length;

    vi.advanceTimersByTime(10_000);
    await flushPromises();
    expect(getUpstreams.mock.calls.length).toBe(settledCalls);
    expect(wrapper.text()).toContain(en.admin.syncOk);
    wrapper.unmount();
  });
});
