import { act } from 'react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, asUser, resetStores, bodyText, allButtons } from './helpers';
import AdminView from '../src/views/AdminView';
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
    getAdminMembershipPlans: vi.fn(async () => []),
    getAdminMembershipOrders: vi.fn(async () => []),
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

async function mountAdmin() {
  asUser();
  const utils = renderInRouter(<AdminView />, { route: '/store/admin' });
  const upstreamsTab = await vi.waitFor(() => {
    const tab = allButtons().find((b) => b.getAttribute('role') === 'tab'
      && b.textContent === en.admin.upstreams);
    expect(tab).toBeTruthy();
    return tab!;
  });
  fireEvent.click(upstreamsTab);
  await vi.waitFor(() => {
    expect(bodyText()).toContain(en.admin.syncSyncing);
  });
  return utils;
}

beforeEach(() => {
  getUpstreams.mockReset();
  getUpstreams.mockResolvedValue([SYNCING, FAILED, OK]);
  getUpstreamSyncRuns.mockReset();
  getUpstreamSyncRuns.mockResolvedValue(RUNS);
});

afterEach(() => {
  vi.useRealTimers();
  resetStores();
});

describe('AdminView upstream sync status (添加上游同步状态)', () => {
  it('renders the syncing state and disables sync-now for an open run', async () => {
    await mountAdmin();
    const text = bodyText();
    expect(text).toContain(en.admin.syncSyncing);
    expect(text).toContain(en.admin.syncOk);
    expect(text).toContain(en.admin.syncFailed);
    // The failed card keeps raw errors off the list — they live in the log.
    expect(text).not.toContain('secret.generic-assignment');
    const syncButtons = allButtons()
      .filter((b) => b.textContent?.trim() === en.admin.syncNow);
    // One button per row; only the SYNCING row's is disabled.
    expect(syncButtons.length).toBe(3);
    expect(syncButtons.filter((b) => b.disabled).length).toBe(1);
  });

  it('shows per-run detail in the log dialog, never inline on the card', async () => {
    await mountAdmin();
    const logButtons = allButtons().filter((b) => b.textContent === en.admin.viewLog);
    expect(logButtons.length).toBe(3);
    // Open the log of the failed source (second card).
    fireEvent.click(logButtons[1]);
    await vi.waitFor(() => {
      const dialog = document.querySelector('[role="dialog"]');
      // Wait for the runs themselves, not just the dialog shell (loading first).
      expect(dialog?.textContent).toContain('secret.generic-assignment');
      expect(dialog?.textContent).toContain('failed with 502');
    });

    const dialog = document.querySelector('[role="dialog"]')!;
    expect(dialog.textContent).toContain(en.admin.syncLogTitle.replace('{name}', FAILED.name));

    fireEvent.click(
      Array.from(dialog.querySelectorAll('button')).find((b) => b.textContent === en.common.close)!,
    );
    await vi.waitFor(() => {
      expect(document.querySelector('[role="dialog"]')).toBeNull();
    });
  });

  it('polls while a run is open and stops once every run settled', async () => {
    vi.useFakeTimers();
    // Mount with fake timers already active so the component's interval uses them.
    await mountAdmin();
    const callsAfterMount = getUpstreams.mock.calls.length;
    expect(callsAfterMount).toBeGreaterThan(0);

    await act(async () => {
      vi.advanceTimersByTime(4100);
    });
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
    await act(async () => {
      vi.advanceTimersByTime(4100);
    });
    const settledCalls = getUpstreams.mock.calls.length;

    await act(async () => {
      vi.advanceTimersByTime(10_000);
    });
    expect(getUpstreams.mock.calls.length).toBe(settledCalls);
    expect(bodyText()).toContain(en.admin.syncOk);
  });
});
