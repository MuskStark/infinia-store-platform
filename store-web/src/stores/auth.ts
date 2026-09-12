import { create } from 'zustand';
import { api, getAccessToken, setAccessToken, type PublicUser } from '../api/client';

/** Auth state — OAuth 2.1 authorization code + PKCE against the store API (design §7.2). */
interface AuthState {
  user: PublicUser | null;
  ready: boolean;
  /** Adopts the user object carried by the login/register response so the UI
   * can navigate immediately; the /me round-trip would leave the sign-in
   * button idle for as long as that request takes. */
  adoptUser: (user: PublicUser) => void;
  load: () => Promise<void>;
  signOut: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  ready: false,
  adoptUser: (user) => set({ user, ready: true }),
  async load() {
    if (!getAccessToken()) {
      set({ user: null, ready: true });
      return;
    }
    try {
      const user = await api.get<PublicUser>('/api/v1/me');
      set({ user });
    } catch {
      setAccessToken(null);
      set({ user: null });
    } finally {
      set({ ready: true });
    }
  },
  signOut() {
    setAccessToken(null);
    set({ user: null });
  },
}));

/** Setters outside React components (tests, inter-store sync). */
export function patchUser(patch: Partial<PublicUser>) {
  const { user } = useAuthStore.getState();
  if (user) {
    useAuthStore.setState({ user: { ...user, ...patch } });
  }
}

/**
 * Component-facing selector with the derived fields the pinia version exposed
 * as getters (isAuthenticated / roles), so views read one shape.
 */
export function useAuth() {
  const user = useAuthStore((s) => s.user);
  const ready = useAuthStore((s) => s.ready);
  return {
    user,
    ready,
    isAuthenticated: user !== null,
    roles: user?.roles ?? [],
    adoptUser: useAuthStore((s) => s.adoptUser),
    load: useAuthStore((s) => s.load),
    signOut: useAuthStore((s) => s.signOut),
  };
}

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
  // The verifier and state are one-shot; never let them linger for the tab's
  // lifetime after the round-trip completes (or fails).
  sessionStorage.removeItem('infinia.store.pkce');
  sessionStorage.removeItem('infinia.store.state');
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
