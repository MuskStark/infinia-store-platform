import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderInRouter } from './helpers';
import StatusRedirectView from '../src/views/StatusRedirectView';
import { redirectToMonitor, resolveMonitorUrl } from '../src/status/monitorHandoff';
import en from '../src/locales/en';

vi.mock('../src/status/monitorHandoff', () => ({
  resolveMonitorUrl: vi.fn(),
  redirectToMonitor: vi.fn(),
}));

// jsdom's Location is fully read-only, so the handoff module (mocked here) is
// the seam; resolveMonitorUrl's own fetch/baked logic lives in
// monitor-handoff.spec.ts against the real module.
describe('StatusRedirectView (store-web /status handoff)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('hands the browser to the resolved monitor address', async () => {
    vi.mocked(resolveMonitorUrl).mockResolvedValue('https://status.example.com');
    renderInRouter(<StatusRedirectView />);
    await vi.waitFor(() => {
      expect(redirectToMonitor).toHaveBeenCalledWith('https://status.example.com');
    });
    expect(document.querySelector('[data-testid="status-unconfigured"]')).toBeNull();
  });

  it('shows a friendly notice instead of raw /api/v1/status JSON when unconfigured', async () => {
    vi.mocked(resolveMonitorUrl).mockResolvedValue(null);
    const { findByTestId } = renderInRouter(<StatusRedirectView />);
    expect(redirectToMonitor).not.toHaveBeenCalled();
    const notice = await findByTestId('status-unconfigured');
    expect(notice.textContent).toContain('STORE_MONITOR_PUBLIC_URL');
    expect(notice.textContent).toContain(en.common.backHome);
  });
});
