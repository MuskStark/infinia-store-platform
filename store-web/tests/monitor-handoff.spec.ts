import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveMonitorUrl } from '../src/status/monitorHandoff';

function mockFetch(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('resolveMonitorUrl (runtime monitor address, ADR-011)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('prefers the runtime-configured address, trimmed of trailing slashes', async () => {
    const fetchMock = mockFetch(200, { url: 'https://status.example.com/' });
    await expect(resolveMonitorUrl()).resolves.toBe('https://status.example.com');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/status/monitor', expect.anything());
  });

  it('falls back to the build-time VITE_MONITOR_BASE_URL when the API has no url', async () => {
    mockFetch(200, { url: null });
    vi.stubEnv('VITE_MONITOR_BASE_URL', 'https://baked-status.example.com//');
    await expect(resolveMonitorUrl()).resolves.toBe('https://baked-status.example.com');
  });

  it('still falls back to the baked address when the probe request itself fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));
    vi.stubEnv('VITE_MONITOR_BASE_URL', 'https://baked-status.example.com');
    await expect(resolveMonitorUrl()).resolves.toBe('https://baked-status.example.com');
  });

  it('resolves null when neither runtime nor build-time config exists', async () => {
    mockFetch(200, { url: null });
    await expect(resolveMonitorUrl()).resolves.toBeNull();
  });
});
