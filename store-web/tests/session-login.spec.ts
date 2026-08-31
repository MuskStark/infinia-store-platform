import { afterEach, describe, expect, it, vi } from 'vitest';
import { submitOAuthSessionLogin } from '../src/auth/sessionLogin';

afterEach(() => {
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

describe('OAuth session login', () => {
  it('posts credentials and CSRF through a top-level form navigation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      parameterName: '_csrf',
      token: 'csrf-token',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

    await submitOAuthSessionLogin('user@infinia.local', 'Password123!');

    expect(fetch).toHaveBeenCalledWith('/oauth2/session-login/csrf', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    const form = document.querySelector('form')!;
    expect(form.getAttribute('action')).toBe('/oauth2/session-login');
    expect(form.getAttribute('method')).toBe('post');
    expect(new FormData(form).get('username')).toBe('user@infinia.local');
    expect(new FormData(form).get('password')).toBe('Password123!');
    expect(new FormData(form).get('_csrf')).toBe('csrf-token');
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('does not submit when CSRF initialization fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })));
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

    await expect(submitOAuthSessionLogin('user@example.com', 'secret'))
      .rejects.toThrow('Cannot initialize OAuth sign-in');
    expect(submit).not.toHaveBeenCalled();
  });
});
