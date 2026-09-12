import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router';
import { useTheme } from 'next-themes';
import { useTranslation } from 'react-i18next';
import { useAuth } from './stores/auth';
import { setLocale } from './i18n';
import BeeLevelBadge from './components/BeeLevelBadge';

/**
 * Marketplace shell. The top bar is themed (translucent warm white / near-black
 * canvas with a light blur, see .header-bar) — the brand mark keeps its own
 * colors from the SVG file. The sign-in page renders full-bleed: no nav bar,
 * no footer band.
 */
export default function App({ children }: { children: React.ReactNode }) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const bareChrome = location.pathname === '/store/signin' || location.pathname === '/';
  const auth = useAuth();
  const { resolvedTheme, setTheme } = useTheme();

  const isDark = resolvedTheme === 'dark';
  const [searchQuery, setSearchQuery] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRoot = useRef<HTMLDivElement | null>(null);

  // Load the session once at boot; guards hold their route until auth.ready.
  useEffect(() => {
    if (!auth.ready) {
      void auth.load();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function toggleTheme() {
    setTheme(isDark ? 'light' : 'dark');
  }

  function switchLocale() {
    setLocale(i18n.language === 'en' ? 'zh-CN' : 'en');
  }

  /** Close on outside click / Escape so the menu behaves like a proper popover. */
  function onDocumentClick(event: MouseEvent) {
    if (menuOpen && menuRoot.current && !menuRoot.current.contains(event.target as Node)) {
      setMenuOpen(false);
    }
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      setMenuOpen(false);
    }
  }

  useEffect(() => {
    document.addEventListener('click', onDocumentClick);
    document.addEventListener('keydown', onKeydown);
    return () => {
      document.removeEventListener('click', onDocumentClick);
      document.removeEventListener('keydown', onKeydown);
    };
  });

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname, location.search, location.hash]);

  function submitSearch(event: React.FormEvent) {
    event.preventDefault();
    navigate({ pathname: '/store/browse', search: searchQuery ? `?q=${encodeURIComponent(searchQuery)}` : '' });
  }

  function goSignOut() {
    setMenuOpen(false);
    auth.signOut();
    navigate('/store');
  }

  const isPublisher = auth.roles.some((r) =>
    ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'].includes(r),
  );
  const isStaff = auth.roles.some((r) => ['REVIEWER', 'PLATFORM_ADMIN'].includes(r));
  const isAdmin = auth.roles.includes('PLATFORM_ADMIN');
  const initial = (auth.user?.displayName ?? auth.user?.email ?? '?').charAt(0).toUpperCase();

  // The primary nav scrolls horizontally when the labels overflow (narrow
  // viewports, English locale). The scrollbar itself is hidden, so overflow is
  // signaled with gradient fades on the clipped edges instead, and the active
  // link is scrolled into view on route changes (the browser otherwise leaves
  // the nav parked wherever a previous focus scroll left it, e.g. first item
  // clipped to “over…”).
  const navEl = useRef<HTMLElement | null>(null);
  const [navFades, setNavFades] = useState({ left: false, right: false });

  const updateNavFades = useCallback(() => {
    const el = navEl.current;
    if (!el) return;
    setNavFades({
      left: el.scrollLeft > 4,
      right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4,
    });
  }, []);

  useEffect(() => {
    const el = navEl.current;
    el?.querySelector('[aria-current="page"], .font-semibold')
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    updateNavFades();
  }, [location.pathname, auth.isAuthenticated, isPublisher, isStaff, isAdmin, updateNavFades]);

  useEffect(() => {
    window.addEventListener('resize', updateNavFades);
    return () => window.removeEventListener('resize', updateNavFades);
  }, [updateNavFades]);

  // Active item: neutral selected pill + gold marker (plan §5), never bold-only.
  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `nav-link inline-flex items-center whitespace-nowrap rounded-lg px-2.5 py-2 transition-colors hover:bg-surface-muted hover:text-ink ${
      isActive ? 'nav-active bg-surface-muted font-semibold text-ink' : 'text-muted'
    }`;

  return (
    <div className="flex min-h-screen flex-col">
      {!bareChrome && (
        <header className="header-bar sticky top-0 z-40">
          <div className="mx-auto flex max-w-[90rem] flex-wrap items-center gap-4 px-4 py-2.5 max-md:gap-y-1">
            <Link to="/store" className="flex shrink-0 items-center gap-2">
              {/* Official Infinia mark, shared with the FengYu host frontend. */}
              <img src="/infinia-logo.svg" alt="" className="h-8 w-8" />
              <span className="text-base tracking-tight">
                <span className="font-bold">Infinia</span>
                <span className="ml-1.5 font-light text-muted">Store</span>
              </span>
            </Link>

            {/* Overflow fades live on a wrapper so the nav keeps its flex sizing
                 while the gradients anchor to the nav's visual box. */}
            <div className="relative min-w-0 max-md:order-last max-md:w-full md:flex-1">
              <nav
                ref={navEl}
                className="nav-scroll flex w-full items-center gap-0.5 overflow-x-auto text-sm"
                aria-label="primary"
                onScroll={updateNavFades}
              >
                {/* inline-flex + items-center: the global 44px touch-target rule
                     stretches these boxes taller than their text, so the label must
                     center inside the box to sit level with the brand logo. */}
                <NavLink end to="/" className={navLinkClass}>{t('nav.introduction')}</NavLink>
                <NavLink end to="/store" className={navLinkClass}>
                  {t('nav.discover')}
                </NavLink>
                <NavLink to="/store/browse" className={navLinkClass}>
                  {t('nav.browse')}
                </NavLink>
                {auth.isAuthenticated && (
                  <NavLink to="/store/library" className={navLinkClass}>
                    {t('nav.library')}
                  </NavLink>
                )}
                {isPublisher && (
                  <NavLink to="/store/publisher" className={navLinkClass}>
                    {t('nav.publisher')}
                  </NavLink>
                )}
                {isStaff && (
                  <NavLink to="/store/review" className={navLinkClass}>
                    {t('nav.review')}
                  </NavLink>
                )}
                {isAdmin && (
                  <NavLink to="/store/admin" className={navLinkClass}>
                    {t('nav.admin')}
                  </NavLink>
                )}
              </nav>
              {navFades.left && (
                <span
                  className="pointer-events-none absolute inset-y-0 left-0 w-8 bg-gradient-to-r from-[var(--header-fade)] to-transparent"
                  aria-hidden="true"
                />
              )}
              {navFades.right && (
                <span
                  className="pointer-events-none absolute inset-y-0 right-0 w-8 bg-gradient-to-l from-[var(--header-fade)] to-transparent"
                  aria-hidden="true"
                />
              )}
            </div>

            <form className="ml-auto hidden w-full max-w-64 md:block" onSubmit={submitSearch}>
              <label className="sr-only" htmlFor="global-search">
                {t('common.search')}
              </label>
              <div className="relative">
                <input
                  id="global-search"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  type="search"
                  placeholder={t('common.search')}
                  className="w-full rounded-lg border border-control/50 bg-surface-muted px-3 py-1.5 text-sm text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none"
                />
                <svg
                  className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-muted"
                  width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"
                >
                  <circle cx="7" cy="7" r="4.5" stroke="currentColor" strokeWidth="1.6" />
                  <path d="M10.5 10.5L14 14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
              </div>
            </form>

            <div className="flex shrink-0 items-center gap-0.5">
              {/* Icon actions share one ghost style tuned for the dark bar. */}
              <button
                className="inline-flex items-center justify-center gap-1.5 rounded-lg px-2.5 py-2 text-sm text-muted transition-colors hover:bg-surface-muted hover:text-ink"
                aria-label={t('common.language')}
                title={t('common.language')}
                onClick={switchLocale}
              >
                <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path
                    d="M3 6h9M7.5 4v2M10 6c-.5 3.5-3 6.5-6 8M5 9.5c1.5 2.5 4 4.5 6.5 5M11.5 16l3.5-8 3.5 8M12.8 13.5h4.4"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                <span className="text-xs font-semibold">{i18n.language === 'en' ? '中' : 'EN'}</span>
              </button>
              <button
                className="inline-flex items-center justify-center rounded-lg px-2.5 py-2 text-muted transition-colors hover:bg-surface-muted hover:text-ink"
                aria-label={t('common.theme')}
                title={t('common.theme')}
                onClick={toggleTheme}
              >
                {isDark ? (
                  <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                    <circle cx="10" cy="10" r="3.5" stroke="currentColor" strokeWidth="1.5" />
                    <path
                      d="M10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M15.3 4.7l-1.4 1.4M6.1 13.9l-1.4 1.4"
                      stroke="currentColor"
                      strokeWidth="1.5"
                      strokeLinecap="round"
                    />
                  </svg>
                ) : (
                  <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                    <path
                      d="M17 12.5A7.5 7.5 0 0 1 7.5 3 7.5 7.5 0 1 0 17 12.5Z"
                      stroke="currentColor"
                      strokeWidth="1.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </button>

              <span className="mx-1.5 hidden h-6 w-px bg-line sm:block" aria-hidden="true" />

              {/* Anonymous: sign-in CTA. Signed-in: the account popover. */}
              {!auth.isAuthenticated ? (
                <button
                  className="btn btn-primary ml-1"
                  onClick={() =>
                    navigate(
                      location.pathname === '/store'
                        ? '/store/signin'
                        : `/store/signin?redirect=${encodeURIComponent(location.pathname + location.search)}`,
                    )
                  }
                >
                  {t('nav.signIn')}
                </button>
              ) : (
                <div ref={menuRoot} className="relative">
                  <button
                    className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-ink transition-colors hover:bg-surface-muted"
                    aria-haspopup="menu"
                    aria-expanded={menuOpen}
                    onClick={() => setMenuOpen(!menuOpen)}
                  >
                    <span
                      className="grid h-7 w-7 place-items-center rounded-lg text-xs font-bold text-white"
                      style={{ background: 'var(--hero-gradient)' }}
                    >
                      {initial}
                    </span>
                    <span className="hidden max-w-36 truncate md:inline">{auth.user?.displayName}</span>
                    <BeeLevelBadge level={auth.user?.effectiveBeeLevel ?? auth.user?.beeLevel ?? 0} compact />
                    <svg
                      className={`text-muted transition-transform ${menuOpen ? 'rotate-180' : ''}`}
                      width="12"
                      height="12"
                      viewBox="0 0 12 12"
                      fill="none"
                      aria-hidden="true"
                    >
                      <path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </button>
                  {menuOpen && (
                    <div
                      className="absolute right-0 z-50 mt-2 w-64 overflow-hidden rounded-xl border border-line bg-surface-raised text-ink shadow-xl shadow-black/10"
                      role="menu"
                    >
                      <div className="border-b border-line px-4 py-3">
                        <p className="truncate text-sm font-semibold">{auth.user?.displayName}</p>
                        <p className="truncate text-xs text-muted">{auth.user?.email}</p>
                        <p className="mt-1.5">
                          <BeeLevelBadge level={auth.user?.effectiveBeeLevel ?? auth.user?.beeLevel ?? 0} />
                        </p>
                      </div>
                      <div className="p-1.5">
                        <button
                          className="block w-full rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted"
                          role="menuitem"
                          onClick={() => {
                            setMenuOpen(false);
                            navigate('/store/account');
                          }}
                        >
                          {t('nav.account')}
                        </button>
                        <Link
                          className="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted"
                          to="/store/library"
                          role="menuitem"
                          onClick={() => setMenuOpen(false)}
                        >
                          {t('nav.library')}
                        </Link>
                        <Link
                          className="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted"
                          to="/store/organizations"
                          role="menuitem"
                          onClick={() => setMenuOpen(false)}
                        >
                          {t('nav.organizations')}
                        </Link>
                        {isPublisher && (
                          <Link
                            className="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted"
                            to="/store/publisher"
                            role="menuitem"
                            onClick={() => setMenuOpen(false)}
                          >
                            {t('nav.publisher')}
                          </Link>
                        )}
                        {isAdmin && (
                          <Link
                            className="block rounded-lg px-2.5 py-2 text-left text-sm hover:bg-surface-muted"
                            to="/store/admin"
                            role="menuitem"
                            onClick={() => setMenuOpen(false)}
                          >
                            {t('nav.admin')}
                          </Link>
                        )}
                      </div>
                      <div className="border-t border-line p-1.5">
                        <button
                          className="block w-full rounded-lg px-2.5 py-2 text-left text-sm text-danger hover:bg-danger/10"
                          role="menuitem"
                          onClick={goSignOut}
                        >
                          {t('nav.signOut')}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </header>
      )}

      <main className="w-full flex-1">
        {children}
      </main>

      {/* Light-gray marketplace footer band: links left, brand right. */}
      {!bareChrome && (
        <footer className="border-t border-line bg-surface-muted">
          <div className="mx-auto flex max-w-[90rem] flex-wrap items-center justify-between gap-3 px-4 py-5 text-xs text-muted">
            <p>
              {t('common.footerTagline')}
              <span aria-hidden="true">·</span>
              <Link className="ml-1 text-accent hover:underline" to="/store/status">
                {t('nav.status')}
              </Link>
            </p>
            <Link to="/store" className="flex items-center gap-2">
              <img src="/infinia-logo.svg" alt="" className="h-6 w-6" />
              <span className="font-semibold text-ink">Infinia Store</span>
            </Link>
          </div>
        </footer>
      )}
    </div>
  );
}
