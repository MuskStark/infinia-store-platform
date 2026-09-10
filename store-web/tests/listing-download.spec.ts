import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import ListingDetailView from '../src/views/ListingDetailView.vue';
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
    { locale: 'en', name: 'Markdown Tools', summary: 'Render and convert Markdown.' },
  ],
  releases: [
    {
      releaseId: RELEASE_ID,
      version: '2.4.0',
      status: 'PUBLISHED',
      channel: 'stable',
      publishedAt: '2026-08-02T00:00:00Z',
      createdAt: '2026-08-02T00:00:00Z',
      license: 'MIT',
      rolloutPercent: 100,
      artifacts: [
        {
          artifactId: 'a1',
          kind: 'PACKAGE',
          filename: 'markdown-2.4.0.fyp',
          platform: 'universal',
          arch: 'universal',
          size: 648,
          sha256: '37ea68338aa76d2eb5d203266fbe199d57a420bf77e1791a273bb4a61287c98b',
          keyId: 'platform-ed25519-2026',
        },
      ],
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

const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } });

const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : \'#\'"><slot /></a>',
};

async function mountDetail() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/listings/official/markdown');
  const wrapper = mount(ListingDetailView, {
    props: { namespace: 'official', slug: 'markdown' },
    global: {
      plugins: [i18n, pinia, router],
      stubs: { RouterLink: RouterLinkStub, teleport: true },
    },
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  downloadMock.mockReset();
  downloadMock.mockResolvedValue('official.markdown-2.4.0-install-package.zip');
});

describe('ListingDetailView offline package download (网页直接下载安装包)', () => {
  it('shows one Get CTA — no duplicate download button beside it', async () => {
    const wrapper = await mountDetail();
    const buttons = wrapper.findAll('button').map((b) => b.text());
    expect(buttons).toContain(en.common.get);
    expect(buttons).not.toContain(en.listing.downloadPackage);
    // The aside still explains where the file installs (主程序本地安装).
    expect(wrapper.text()).toContain(en.listing.downloadPackageHint);
    wrapper.unmount();
  });

  it('Get → confirm installs by downloading the offline install package', async () => {
    const wrapper = await mountDetail();
    await wrapper.findAll('button').find((b) => b.text() === en.common.get)!.trigger('click');
    await flushPromises();

    // Permission-aware confirm step (design §9.3) before any download.
    expect(wrapper.text()).toContain(en.listing.confirmInstall);
    expect(wrapper.text()).toContain(pluginDetail.coordinate);

    // Install fake timers BEFORE the click — the 600ms verifying pause must
    // be scheduled on them for advanceTimersByTimeAsync to skip it.
    vi.useFakeTimers();
    try {
      await wrapper.findAll('button')
        .find((b) => b.text() === en.common.confirm)!.trigger('click');
      await vi.advanceTimersByTimeAsync(700);
    } finally {
      vi.useRealTimers();
    }
    await flushPromises();
    expect(downloadMock).toHaveBeenCalledTimes(1);
    expect(downloadMock).toHaveBeenCalledWith(
      `/api/v1/releases/${RELEASE_ID}/install-package`,
      'official.markdown.zip',
    );
    // Done state points the user at the host's local install mode.
    expect(wrapper.text()).toContain(en.listing.packageDownloaded);
    wrapper.unmount();
  });

  it('a failed package download names the download, not the resolution', async () => {
    downloadMock.mockRejectedValueOnce(new Error('boom'));
    const wrapper = await mountDetail();
    await wrapper.findAll('button').find((b) => b.text() === en.common.get)!.trigger('click');
    await flushPromises();
    await wrapper.findAll('button').find((b) => b.text() === en.common.confirm)!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain(en.listing.installDownloadFailed);
    expect(wrapper.text()).not.toContain(en.listing.resolveFailed);
    wrapper.unmount();
  });

  it('keeps per-release direct downloads on the versions tab, retryable after failure', async () => {
    downloadMock.mockRejectedValueOnce(new Error('boom'));
    const wrapper = await mountDetail();
    await wrapper.findAll('button').find((b) => b.text() === en.listing.versions)!.trigger('click');

    const packageButton = () =>
      wrapper.findAll('button').find((b) => b.text() === en.listing.downloadPackage)!;
    expect(wrapper.findAll('button')
      .filter((b) => b.text() === en.listing.downloadPackage).length).toBe(1);

    await packageButton().trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain(en.listing.packageDownloadFailed);

    // The button is not disabled by the error state — a second click retries.
    expect(packageButton().attributes('disabled')).toBeUndefined();
    await packageButton().trigger('click');
    await flushPromises();
    expect(downloadMock).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).not.toContain(en.listing.packageDownloadFailed);
    wrapper.unmount();
  });
});
