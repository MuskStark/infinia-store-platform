import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import StatusRedirectView from '../src/views/StatusRedirectView.vue';
import { redirectToMonitor, resolveMonitorUrl } from '../src/status/monitorHandoff';
import en from '../src/locales/en';

vi.mock('../src/status/monitorHandoff', () => ({
  resolveMonitorUrl: vi.fn(),
  redirectToMonitor: vi.fn(),
}));

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en },
});

// jsdom's Location is fully read-only, so the handoff module (mocked here) is
// the seam; resolveMonitorUrl's own fetch/baked logic lives in
// monitor-handoff.spec.ts against the real module.
describe('StatusRedirectView (store-web /status handoff)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('hands the browser to the resolved monitor address', async () => {
    vi.mocked(resolveMonitorUrl).mockResolvedValue('https://status.example.com');
    const wrapper = mount(StatusRedirectView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(redirectToMonitor).toHaveBeenCalledWith('https://status.example.com');
    expect(wrapper.find('[data-testid="status-unconfigured"]').exists()).toBe(false);
  });

  it('shows a friendly notice instead of raw /api/v1/status JSON when unconfigured', async () => {
    vi.mocked(resolveMonitorUrl).mockResolvedValue(null);
    const wrapper = mount(StatusRedirectView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(redirectToMonitor).not.toHaveBeenCalled();
    const notice = wrapper.find('[data-testid="status-unconfigured"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('STORE_MONITOR_PUBLIC_URL');
  });
});
