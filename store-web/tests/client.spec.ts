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
});
