import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useTheme } from 'next-themes';
import { useTranslation } from 'react-i18next';
import {
  api,
  ApiRequestError,
  setAccessToken,
  type PublicUser,
} from '../api/client';
import { useAuth } from '../stores/auth';
import { submitOAuthSessionLogin } from '../auth/sessionLogin';
import MagicCard from '../components/MagicCard';
import { HexagonPattern } from '../components/magicui/hexagon-pattern';
import { cn } from '@/lib/utils';

type HexCoord = [col: number, row: number];

/**
 * Cell grid bounds for the full-bleed wall (radius 48, vertical tiling):
 * ~15 columns × 10 rows at 1280×720. Random picks stay in the left ⅔ where
 * the mask keeps the comb visible — the right side is faded out anyway.
 */
const WALL_COLS = 12;
const WALL_ROWS = 9;

function rollHexagons(count: number): HexCoord[] {
  return Array.from(
    { length: count },
    () =>
      [
        Math.floor(Math.random() * WALL_COLS),
        Math.floor(Math.random() * WALL_ROWS),
      ] as HexCoord,
  );
}

/**
 * Sign-in / registration (design §7.4).
 *
 * Normal Store sign-in uses the direct token endpoint. Host OAuth requests arrive
 * with ?oauth=1 and establish the Authorization Server browser session here before
 * Spring resumes the saved PKCE authorization request.
 */
export default function SignInView() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const auth = useAuth();
  const { resolvedTheme } = useTheme();

  const [mode, setMode] = useState<'signin' | 'register'>('signin');
  const [busy, setBusy] = useState(false);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const oauthMode = searchParams.get('oauth') === '1';
  const [error, setError] = useState<string | null>(
    searchParams.get('error') === '1' ? t('errors.invalid_credentials') : null,
  );
  const [notice, setNotice] = useState<string | null>(null);

  // Living wall: every layer rolls ONCE per page load and then stays mounted —
  // re-rolling polygons while they're visible made cells pop in and out.
  // Instead, the .hex-lit layer breathes per-cell AND cycles as a group: it
  // fades fully dark every 5s (dark plateau ≈ 3.8s–4.5s of the CSS cycle),
  // and this timer — aligned to the same period — silently re-rolls the cell
  // positions inside that plateau. The glows re-ignite at random spots.
  // Under prefers-reduced-motion the wall holds a steady glow, no re-rolls.
  const [deepCells] = useState(() =>
    rollHexagons(3 + Math.floor(Math.random() * 2)),
  );
  const [softCells] = useState(() =>
    rollHexagons(4 + Math.floor(Math.random() * 2)),
  );
  const [litHexagons, setLitHexagons] = useState(() =>
    rollHexagons(2 + Math.floor(Math.random() * 2)),
  );
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    let interval: number | undefined;
    const reroll = () =>
      setLitHexagons(rollHexagons(2 + Math.floor(Math.random() * 2)));
    // First swap at the cycle's first dark plateau, then every cycle after.
    const timeout = window.setTimeout(() => {
      reroll();
      interval = window.setInterval(reroll, 5000);
    }, 4050);
    return () => {
      window.clearTimeout(timeout);
      if (interval !== undefined) {
        window.clearInterval(interval);
      }
    };
  }, []);

  /** Seeded demo accounts exist only under local/dev profiles, and the panel
   * only makes sense then: production seeds nothing. The literals sit behind
   * the DEV constant so the production build tree-shakes them out entirely. */
  const showDemoAccounts = import.meta.env.DEV;
  const demoAccounts = useMemo(
    () =>
      showDemoAccounts
        ? [
            {
              email: 'admin@infinia.local',
              password: 'Password123!',
              label: t('role.PLATFORM_ADMIN'),
            },
            {
              email: 'reviewer@infinia.local',
              password: 'Password123!',
              label: t('role.REVIEWER'),
            },
            {
              email: 'publisher@infinia.local',
              password: 'Password123!',
              label: t('role.PUBLISHER'),
            },
            {
              email: 'user@infinia.local',
              password: 'Password123!',
              label: t('role.USER'),
            },
          ]
        : ([] as { email: string; password: string; label: string }[]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [showDemoAccounts],
  );

  const emailInvalid =
    email.length > 0 && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email);
  const passwordShort =
    mode === 'register' && password.length > 0 && password.length < 8;
  const passwordMismatch =
    passwordConfirm.length > 0 && passwordConfirm !== password;
  const formInvalid = (() => {
    if (emailInvalid || !email || !password) return true;
    if (mode === 'register') {
      return passwordShort || passwordMismatch;
    }
    return false;
  })();

  function switchMode(next: 'signin' | 'register') {
    setMode(next);
    setError(null);
    setNotice(null);
  }

  /** Fills the form from a demo account row; carries no literals itself so it
   * stays in the bundle while the credentials do not. */
  function useDemoAccount(demo: { email: string; password: string }) {
    setMode('signin');
    setError(null);
    setNotice(null);
    setEmail(demo.email);
    setPassword(demo.password);
  }

  function problemText(e: unknown): string {
    if (e instanceof ApiRequestError && e.code) {
      const key = `errors.${e.code}`;
      const localized = t(key);
      const text = localized !== key ? localized : (e.detail ?? e.message);
      // Validation failures keep their server-side cause (e.g. which field
      // failed) — the bare localized title alone is not actionable.
      if (
        e.code === 'validation_failed' &&
        e.detail &&
        !text.includes(e.detail)
      ) {
        return `${text}：${e.detail}`;
      }
      return text;
    }
    return t('errors.server');
  }

  async function finishLogin(token: string, user?: PublicUser) {
    setAccessToken(token);
    // The login response already carries the user: navigate at once instead of
    // waiting on a second /me round-trip that leaves the form looking dead.
    if (user) {
      auth.adoptUser(user);
    } else {
      await auth.load();
    }
    const redirect = searchParams.get('redirect') ?? '/store';
    try {
      await navigate(redirect);
    } catch {
      // A SPA navigation can fail after a redeploy (stale shell referencing
      // removed chunks). A full page load re-fetches the shell and still lands
      // the user where they asked to go — never stranded on the sign-in page.
      window.location.assign(redirect);
    }
  }

  async function signIn(event?: React.FormEvent) {
    event?.preventDefault();
    if (formInvalid || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (oauthMode) {
        await submitOAuthSessionLogin(email, password);
        return;
      }
      const response = await api.post<{
        accessToken: string;
        user?: PublicUser;
      }>('/api/v1/auth/login', { email, password });
      await finishLogin(response.accessToken, response.user);
    } catch (e) {
      setError(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  async function register(event?: React.FormEvent) {
    event?.preventDefault();
    if (formInvalid || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.post('/api/v1/auth/register', {
        email,
        password,
        displayName: displayName || undefined,
      });
      if (oauthMode) {
        await submitOAuthSessionLogin(email, password);
        return;
      }
      // Register → immediately signed in with the same credentials.
      const response = await api.post<{
        accessToken: string;
        user?: PublicUser;
      }>('/api/v1/auth/login', {
        email,
        password,
      });
      await finishLogin(response.accessToken, response.user);
    } catch (e) {
      setError(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    // Full-bleed hive wall: the comb texture covers the whole page and fades
    // toward the right (strongest on the left), the form floats on top of it.
    <div className="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-surface-muted px-4 py-8">
      {/* The wall fades left → right via a mask, matching the reference wash. */}
      <div
        className="pointer-events-none absolute inset-0"
        aria-hidden="true"
        style={{
          maskImage:
            'linear-gradient(to right, black 0%, rgba(0,0,0,0.55) 45%, transparent 100%)',
          WebkitMaskImage:
            'linear-gradient(to right, black 0%, rgba(0,0,0,0.55) 45%, transparent 100%)',
        }}
      >
        {/* Original Magic UI HexagonPattern: pointy-top tiling (direction
      "vertical") plus shade blocks of varying depth and the breathing
      lit layer. Peach stroke / soft wax per the reference. */}
        <HexagonPattern
          radius={48}
          direction="vertical"
          className={cn(
            resolvedTheme === 'dark'
              ? 'stroke-[rgba(252,128,29,0.45)]'
              : 'stroke-[rgba(252,128,29,0.26)]',
          )}
        />
        <HexagonPattern
          radius={48}
          direction="vertical"
          hexagons={deepCells}
          className={cn(
            'stroke-none',
            resolvedTheme === 'dark'
              ? 'fill-[rgba(252,128,29,0.32)]'
              : 'fill-[rgba(252,128,29,0.20)]',
          )}
        />
        <HexagonPattern
          radius={48}
          direction="vertical"
          hexagons={softCells}
          className={cn(
            'stroke-none',
            resolvedTheme === 'dark'
              ? 'fill-[rgba(252,128,29,0.16)]'
              : 'fill-[rgba(252,128,29,0.09)]',
          )}
        />
        <HexagonPattern
          radius={48}
          direction="vertical"
          hexagons={litHexagons}
          className={cn(
            'hex-lit stroke-none',
            resolvedTheme === 'dark'
              ? 'fill-[rgba(252,128,29,0.55)]'
              : 'fill-[rgba(252,128,29,0.22)]',
          )}
        />
      </div>

      {/* Brand + form, centered on the wall — with the original welcome text. */}
      <div className="relative mb-6 flex flex-col items-center gap-2.5 text-center">
        <img src="/infinia-logo.svg" alt="" className="h-11 w-11" />
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.25em] text-muted">
            Infinia Store
          </p>
          <h2 className="mt-1.5 text-4xl font-bold tracking-tight text-ink">
            {t('auth.brandTitle')}
          </h2>
        </div>
        <p className="max-w-xl text-sm leading-6 text-muted">
          {t('discover.heroSubtitle')}
        </p>
      </div>

      <div className="relative w-full max-w-md">
        <MagicCard className="rounded-lg p-8">
          <nav
            className="mb-6 flex gap-1 rounded-xl bg-surface-muted p-1"
            role="tablist"
            aria-label={t('auth.signInTitle')}
          >
            {(['signin', 'register'] as const).map((key) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={mode === key}
                className={cn(
                  'flex-1 rounded-lg px-4 py-2 text-sm font-medium',
                  mode === key ? 'bg-surface shadow-sm' : 'text-muted',
                )}
                onClick={() => switchMode(key)}
              >
                {key === 'signin' ? t('nav.signIn') : t('auth.register')}
              </button>
            ))}
          </nav>

          <form
            className="space-y-4"
            noValidate
            onSubmit={(e) =>
              mode === 'signin' ? void signIn(e) : void register(e)
            }
          >
            <label className="block text-sm">
              {t('auth.email')}
              <input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                type="email"
                required
                autoComplete="username"
                aria-invalid={emailInvalid || undefined}
                placeholder="you@example.com"
                className="input mt-1"
              />
              {emailInvalid && (
                <span className="mt-1 block text-xs text-red-600">
                  {t('auth.emailInvalid')}
                </span>
              )}
            </label>

            <label className="block text-sm">
              {t('auth.password')}
              <span className="relative mt-1 block">
                <input
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  type={showPassword ? 'text' : 'password'}
                  required
                  minLength={mode === 'register' ? 8 : undefined}
                  autoComplete={
                    mode === 'register' ? 'new-password' : 'current-password'
                  }
                  aria-invalid={passwordShort || undefined}
                  placeholder="••••••••"
                  className="input pr-16!"
                />
                <button
                  type="button"
                  className="absolute inset-y-0 right-2 my-auto rounded-lg px-2 text-xs text-muted"
                  aria-label={t('auth.togglePassword')}
                  onClick={() => setShowPassword(!showPassword)}
                >
                  {showPassword ? t('auth.hide') : t('auth.show')}
                </button>
              </span>
              {passwordShort && (
                <span className="mt-1 block text-xs text-red-600">
                  {t('auth.passwordShort')}
                </span>
              )}
            </label>

            {mode === 'register' && (
              <>
                <label className="block text-sm">
                  {t('auth.passwordConfirm')}
                  <input
                    value={passwordConfirm}
                    onChange={(e) => setPasswordConfirm(e.target.value)}
                    type={showPassword ? 'text' : 'password'}
                    required
                    autoComplete="new-password"
                    aria-invalid={passwordMismatch || undefined}
                    className="input mt-1"
                  />
                  {passwordMismatch && (
                    <span className="mt-1 block text-xs text-red-600">
                      {t('auth.passwordMismatch')}
                    </span>
                  )}
                </label>

                <label className="block text-sm">
                  {t('account.displayName')}
                  <input
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    autoComplete="nickname"
                    placeholder={t('auth.displayNamePlaceholder')}
                    className="input mt-1"
                  />
                </label>
              </>
            )}

            <button
              type="submit"
              className="btn btn-primary h-11 w-full"
              disabled={busy || formInvalid}
            >
              {busy
                ? t('common.loading')
                : mode === 'signin'
                  ? t('nav.signIn')
                  : t('auth.register')}
            </button>
          </form>

          {error && (
            <p className="alert alert-error mt-4" role="alert">
              {error}
            </p>
          )}
          {!error && notice && (
            <p className="alert alert-info mt-4" role="status">
              {notice}
            </p>
          )}

          {mode === 'signin' && showDemoAccounts && (
            <details className="mt-5 text-sm">
              <summary className="cursor-pointer select-none text-muted hover:text-accent">
                {t('auth.demoAccounts')}
              </summary>
              <ul className="mt-2 space-y-1">
                {demoAccounts.map((demo) => (
                  <li key={demo.email}>
                    <button
                      type="button"
                      className="flex w-full items-center justify-between rounded-lg border border-line px-3 py-2 text-left text-xs hover:bg-surface-muted"
                      onClick={() => useDemoAccount(demo)}
                    >
                      <code>{demo.email}</code>
                      <span className="text-muted">{demo.label}</span>
                    </button>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-xs text-muted">{t('auth.demoHint')}</p>
            </details>
          )}

          <p className="mt-4 text-center text-sm">
            {mode === 'signin' ? (
              <>
                {t('auth.noAccount')}
                {` `}
                <button
                  className="font-semibold text-accent"
                  onClick={() => switchMode('register')}
                >
                  {t('auth.register')}
                </button>
              </>
            ) : (
              <>
                {t('auth.haveAccount')}
                {` `}
                <button
                  className="font-semibold text-accent"
                  onClick={() => switchMode('signin')}
                >
                  {t('nav.signIn')}
                </button>
              </>
            )}
          </p>
        </MagicCard>
      </div>
    </div>
  );
}
