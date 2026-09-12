import { act } from 'react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, bodyText, buttonByText, allButtons } from './helpers';
import ListingDetailView from '../src/views/ListingDetailView';
import en from '../src/locales/en';

/**
 * Web download of offline install packages (goal: 从网页直接下载插件等制品).
 * One CTA: the 获取/Get button runs the permission-aware resolve → confirm
 * machine and downloads the install package on confirm — no second download
 * button next to it. The versions tab keeps per-release direct downloads.
 */

const RELEASE_ID = '11111111-1111-7111-8111-111111111111';

const pluginDetail = {
  listingId: 'l1',
  coordinate: 'infinia://plugin/official/markdown',
  type: 'PLUGIN',
  namespace: 'official',
  slug: 'markdown',
  visibility: 'PUBLIC',
  status: 'ACTIVE',
  category: 'Productivity',
  tags: ['markdown'],
  screenshots: [],
  defaultChannel: 'stable',
  publisherName: 'official',
  downloads: 12,
  favorites: 2,
  minBeeLevel: 0,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-02T00:00:00Z',
  localizations: [
    {
      locale: 'en',
      name: 'Markdown Tools',
      summary: 'Render and convert Markdown',
      descriptionMarkdown: '# Markdown Tools\n\nRender and convert Markdown files.',
    },
  ],
  releases: [
    {
      releaseId: RELEASE_ID,
      version: '2.4.0',
      status: 'PUBLISHED',
      channel: 'stable',
      rolloutPercent: 100,
      createdAt: '2026-08-02T00:00:00Z',
      publishedAt: '2026-08-02T00:00:00Z',
      changelogMarkdown: 'Fixes.',
      permissions: [
        { permissionId: 'files.read', scope: 'fs:~/.fengyu/plugins/markdown', required: true },
      ],
      dependencies: [],
    },
  ],
};

const downloadMock = vi.fn(async (_path: string, _fallbackName: string) =>
  'official.markdown-2.4.0-install-package.zip');

vi.mock('../src/api/client', () => ({
  api: {
    get: vi.fn(async (path: string) => {
      if (path.startsWith('/api/v1/listings/official/markdown')) {
        if (path.endsWith('/ratings')) {
          return { summary: { average: 5, count: 1 }, ratings: [] };
        }
        return pluginDetail;
      }
      throw new Error('unexpected GET ' + path);
    }),
    post: vi.fn(async (path: string) => {
      if (path === '/api/v1/resolutions') {
        return {
          resolvable: true,
          plan: [{ coordinate: pluginDetail.coordinate, alreadyInstalled: false }],
          missing: [],
        };
      }
      throw new Error('unexpected POST ' + path);
    }),
    download: (path: string, fallbackName: string) => downloadMock(path, fallbackName),
  },
  ApiRequestError: class extends Error {},
  setAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
}));

function mountDetail() {
  return renderInRouter(<ListingDetailView />, {
    route: '/store/listing/official/markdown',
    path: '/store/listing/:namespace/:slug',
  });
}

async function mountedDetail() {
  const utils = mountDetail();
  await vi.waitFor(() => {
    expect(bodyText()).toContain('Markdown Tools');
  });
  return utils;
}

beforeEach(() => {
  downloadMock.mockReset();
  downloadMock.mockResolvedValue('official.markdown-2.4.0-install-package.zip');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('ListingDetailView offline package download (网页直接下载安装包)', () => {
  it('shows one Get CTA — no duplicate download button beside it', async () => {
    await mountedDetail();
    const buttons = allButtons().map((b) => b.textContent);
    expect(buttons).toContain(en.common.get);
    expect(buttons).not.toContain(en.listing.downloadPackage);
    // The aside still explains where the file installs (主程序本地安装).
    expect(bodyText()).toContain(en.listing.downloadPackageHint);
  });

  it('Get → confirm installs by downloading the offline install package', async () => {
    await mountedDetail();
    fireEvent.click(buttonByText(en.common.get)!);
    await vi.waitFor(() => {
      // Permission-aware confirm step (design §9.3) before any download.
      expect(bodyText()).toContain(en.listing.confirmInstall);
    });
    expect(bodyText()).toContain(pluginDetail.coordinate);

    // Install fake timers BEFORE the click — the 600ms verifying pause must
    // be scheduled on them for advanceTimersByTime to skip it.
    vi.useFakeTimers();
    try {
      fireEvent.click(buttonByText(en.common.confirm)!);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(700);
      });
    } finally {
      vi.useRealTimers();
    }
    await vi.waitFor(() => {
      expect(downloadMock).toHaveBeenCalledTimes(1);
    });
    expect(downloadMock).toHaveBeenCalledWith(
      `/api/v1/releases/${RELEASE_ID}/install-package`,
      'official.markdown.zip',
    );
    // Done state points the user at the host's local install mode.
    expect(bodyText()).toContain(en.listing.packageDownloaded);
  });

  it('a failed package download names the download, not the resolution', async () => {
    downloadMock.mockRejectedValueOnce(new Error('boom'));
    await mountedDetail();
    fireEvent.click(buttonByText(en.common.get)!);
    await vi.waitFor(() => {
      expect(bodyText()).toContain(en.listing.confirmInstall);
    });
    fireEvent.click(buttonByText(en.common.confirm)!);
    await vi.waitFor(() => {
      expect(bodyText()).toContain(en.listing.installDownloadFailed);
    });
    expect(bodyText()).not.toContain(en.listing.resolveFailed);
  });

  it('keeps per-release direct downloads on the versions tab, retryable after failure', async () => {
    downloadMock.mockRejectedValueOnce(new Error('boom'));
    await mountedDetail();
    fireEvent.click(buttonByText(en.listing.versions)!);

    const downloadButtons = () =>
      allButtons().filter((b) => b.textContent === en.listing.downloadPackage);
    expect(downloadButtons().length).toBe(1);

    fireEvent.click(downloadButtons()[0]);
    await vi.waitFor(() => {
      expect(bodyText()).toContain(en.listing.packageDownloadFailed);
    });

    // The button is not disabled by the error state — a second click retries.
    expect(downloadButtons()[0].disabled).toBe(false);
    fireEvent.click(downloadButtons()[0]);
    await vi.waitFor(() => {
      expect(downloadMock).toHaveBeenCalledTimes(2);
    });
    expect(bodyText()).not.toContain(en.listing.packageDownloadFailed);
  });
});
