import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiRequestError, setAccessToken } from '../src/api/client';

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

afterEach(() => {
  vi.unstubAllGlobals();
  setAccessToken(null);
});

describe('api client', () => {
  it('sends the bearer token when present', async () => {
    setAccessToken('token-123');
    const fetchMock = mockFetch(200, []);
    await api.get('/api/v1/catalog');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer token-123');
  });

  it('omits auth for anonymous requests', async () => {
    const fetchMock = mockFetch(200, { items: [] });
    await api.get('/api/v1/catalog');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new Headers(init.headers).get('Authorization')).toBeNull();
  });

  it('throws ApiRequestError with the stable code for problem+json', async () => {
    mockFetch(409, {
      code: 'email_taken',
      title: 'Email already registered',
      status: 409,
      traceId: 't-1',
    });
    const error = await api.post('/api/v1/auth/register', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiRequestError);
    expect((error as ApiRequestError).code).toBe('email_taken');
    expect((error as ApiRequestError).status).toBe(409);
  });

  it('JSON-encodes request bodies with the right content type', async () => {
    const fetchMock = mockFetch(202, 1);
    await api.post('/api/v1/install-events', [{ idempotencyKey: 'k' }]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/install-events');
    expect(init.body).toBe(JSON.stringify([{ idempotencyKey: 'k' }]));
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
  });

  it('download saves the Content-Disposition filename via a synthetic anchor', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['zip-bytes']), {
        status: 200,
        headers: {
          'content-type': 'application/zip',
          'content-disposition': 'attachment; filename="official.markdown-2.4.0-install-package.zip"',
        },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    // jsdom lacks createObjectURL — patch, then restore so later tests are unaffected.
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:mock');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    try {
      const name = await api.download('/api/v1/releases/r1/install-package', 'fallback.zip');

      expect(name).toBe('official.markdown-2.4.0-install-package.zip');
      const anchor = click.mock.instances.at(-1) as HTMLAnchorElement;
      expect(anchor.download).toBe('official.markdown-2.4.0-install-package.zip');
      expect(anchor.href).toBe('blob:mock');
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock');
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });

  it('download surfaces problem+json errors as ApiRequestError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'bee_level_required', status: 403 }), {
        status: 403,
        headers: { 'content-type': 'application/problem+json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const error = await api.download('/api/v1/releases/r1/install-package', 'f.zip')
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiRequestError);
    expect((error as ApiRequestError).code).toBe('bee_level_required');
    expect((error as ApiRequestError).status).toBe(403);
  });
});
