import { defineStore } from 'pinia';
import { api, getAccessToken, setAccessToken, type PublicUser } from '../api/client';

/** Auth state — OAuth 2.1 authorization code + PKCE against the store API (design §7.2). */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as PublicUser | null,
    ready: false,
  }),
  getters: {
    isAuthenticated: (state) => state.user !== null,
    roles: (state): string[] => state.user?.roles ?? [],
  },
  actions: {
    async load() {
      if (!getAccessToken()) {
        this.user = null;
        this.ready = true;
        return;
      }
      try {
        this.user = await api.get<PublicUser>('/api/v1/me');
      } catch {
        setAccessToken(null);
        this.user = null;
      } finally {
        this.ready = true;
      }
    },
    signOut() {
      setAccessToken(null);
      this.user = null;
    },
  },
});

/** PKCE helpers for the SPA login flow. */
function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function sha256(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
  return base64Url(new Uint8Array(digest));
}

export async function beginLogin() {
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(48)));
  const state = base64Url(crypto.getRandomValues(new Uint8Array(12)));
  const challenge = await sha256(verifier);
  sessionStorage.setItem('infinia.store.pkce', verifier);
  sessionStorage.setItem('infinia.store.state', state);
  const redirectUri = `${location.origin}/callback`;
  location.assign(
    `/oauth2/authorize?response_type=code&client_id=store-web&redirect_uri=${encodeURIComponent(
      redirectUri,
    )}&scope=openid&state=${state}&code_challenge=${challenge}&code_challenge_method=S256`,
  );
}

export async function completeLogin(code: string, state: string): Promise<boolean> {
  const verifier = sessionStorage.getItem('infinia.store.pkce');
  const expectedState = sessionStorage.getItem('infinia.store.state');
  if (!verifier || state !== expectedState) {
    return false;
  }
  const response = await fetch('/oauth2/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: `${location.origin}/callback`,
      client_id: 'store-web',
      code_verifier: verifier,
    }),
  });
  if (!response.ok) {
    return false;
  }
  const token = (await response.json()) as { access_token: string };
  setAccessToken(token.access_token);
  return true;
}
