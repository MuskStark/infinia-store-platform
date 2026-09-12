import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, type InstalledItem } from '../api/client';
import { useLibraryStore } from '../stores/library';
import { Badge } from '@/components/ui/badge';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import LoadingGrid from '../components/LoadingGrid';
import PageHeader from '../components/PageHeader';
import SelectMenu from '../components/SelectMenu';
import Tabs from '../components/Tabs';
import { formatDate, formatDateTime } from '../utils/format';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

type LibraryTab = 'installed' | 'favorites' | 'entitlements' | 'history';

/** infinia://plugin/official/markdown → { type, namespace, slug }. */
function splitCoordinate(coordinate: string): {
  type: string;
  namespace: string;
  slug: string;
} {
  const [type = '', namespace = '', slug = ''] = coordinate
    .replace(/^infinia:\/\//, '')
    .split('/');
  return { type, namespace, slug };
}

/** infinia://plugin/official/markdown → /listing/official/markdown */
function coordinateRoute(coordinate?: string): string {
  const { namespace, slug } = splitCoordinate(coordinate ?? '');
  return namespace && slug ? `/store/listing/${namespace}/${slug}` : '/store/browse';
}

/** The product name is the first reading layer; the slug is the best label
 * available when the API doesn't carry a display name for installed items. */
function displayName(coordinate?: string, name?: string): string {
  if (name) return name;
  const { slug } = splitCoordinate(coordinate ?? '');
  return slug ? slug.replace(/[-_]/g, ' ') : (coordinate ?? '');
}

/** Copy the raw coordinate to the clipboard; surface success AND failure —
 * a rejected clipboard permission must not look like a silent no-op. */
function CopyCoordinate({ value }: { value: string }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState<'ok' | 'fail' | null>(null);
  return (
    <button
      type="button"
      className="tap-compact inline-flex shrink-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-xs text-muted transition-colors hover:bg-surface-muted hover:text-ink"
      title={t('library.copyCoordinate')}
      onClick={() => {
        if (!navigator.clipboard?.writeText) {
          setCopied('fail');
        } else {
          navigator.clipboard
            .writeText(value)
            .then(() => setCopied('ok'))
            .catch(() => setCopied('fail'));
        }
        setTimeout(() => setCopied(null), 1800);
      }}
    >
      <code className="max-w-[22rem] truncate" title={value}>
        {value}
      </code>
      <svg
        viewBox={copied ? '0 0 16 16' : '0 0 24 24'}
        width="13"
        height="13"
        fill="none"
        aria-hidden="true"
        className="shrink-0"
      >
        {copied === 'ok' ? (
          <path
            d="M3 8.5 6.5 12 13 4.5"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ) : copied === 'fail' ? (
          <path
            d="M4 4l8 8M12 4l-8 8"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
        ) : (
          <>
            <rect
              x="9"
              y="9"
              width="11"
              height="11"
              rx="2"
              stroke="currentColor"
              strokeWidth="1.7"
            />
            <path
              d="M5 15V5a2 2 0 0 1 2-2h10"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinecap="round"
            />
          </>
        )}
      </svg>
      {copied === 'fail' && (
        <span className="text-danger">{t('library.copyFailed')}</span>
      )}
    </button>
  );
}

export default function LibraryView() {
  const { t } = useTranslation();
  const library = useLibraryStore();
  const [installed, setInstalled] = useState<InstalledItem[]>([]);
  /** /me/installed is a separate request with its own lifecycle — it must not
   * inherit the library-store spinner, nor paint "nothing installed" while
   * in flight or on failure (plan §6.4). */
  const [installedLoading, setInstalledLoading] = useState(true);
  const [installedError, setInstalledError] = useState<string | null>(null);
  const [tab, setTab] = useState<LibraryTab>('installed');
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [updatesOnly, setUpdatesOnly] = useState(false);

  async function reload() {
    setInstalledLoading(true);
    setInstalledError(null);
    try {
      const installedList = await api.get<InstalledItem[]>(
        '/api/v1/me/installed',
      );
      setInstalled(installedList);
    } catch (e) {
      setInstalledError(e instanceof Error ? e.message : String(e));
    } finally {
      setInstalledLoading(false);
    }
  }

  useEffect(() => {
    void library.load();
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const updates = useMemo(
    () => installed.filter((item) => item.updateAvailable),
    [installed],
  );

  /** Favorites carry display names — reuse them for installed items by coordinate. */
  const namesByCoordinate = useMemo(() => {
    const map = new Map<string, string>();
    for (const favorite of library.library?.favorites ?? []) {
      if (favorite.listingCoordinate && favorite.name) {
        map.set(favorite.listingCoordinate, favorite.name);
      }
    }
    return map;
  }, [library.library?.favorites]);

  const types = useMemo(
    () => [
      ...new Set(
        [
          ...installed.map((item) => item.type),
          ...(library.library?.favorites ?? []).map(
            (favorite) => favorite.type,
          ),
        ].filter((type): type is string => Boolean(type)),
      ),
    ],
    [installed, library.library?.favorites],
  );

  const filteredInstalled = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (
      [...installed]
        .filter((item) => (updatesOnly ? item.updateAvailable : true))
        .filter((item) =>
          typeFilter === 'ALL' ? true : item.type === typeFilter,
        )
        .filter((item) =>
          !q
            ? true
            : displayName(
                item.coordinate,
                namesByCoordinate.get(item.coordinate ?? ''),
              )
                .toLowerCase()
                .includes(q) ||
              (item.coordinate ?? '').toLowerCase().includes(q),
        )
        // Updatable items float to the top of the installed list.
        .sort(
          (a, b) =>
            Number(b.updateAvailable ?? false) -
            Number(a.updateAvailable ?? false),
        )
    );
  }, [installed, query, typeFilter, updatesOnly, namesByCoordinate]);

  function clearFilters() {
    setQuery('');
    setTypeFilter('ALL');
    setUpdatesOnly(false);
  }

  const filteredFavorites = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (library.library?.favorites ?? [])
      .filter((favorite) =>
        typeFilter === 'ALL' ? true : favorite.type === typeFilter,
      )
      .filter((favorite) =>
        !q
          ? true
          : (favorite.name ?? '').toLowerCase().includes(q) ||
            (favorite.listingCoordinate ?? '').toLowerCase().includes(q),
      );
  }, [library.library?.favorites, query, typeFilter]);

  const TABS: { id: LibraryTab; label: string }[] = [
    { id: 'installed', label: t('library.installed') },
    { id: 'favorites', label: t('library.favorites') },
    { id: 'entitlements', label: t('library.entitlements') },
    { id: 'history', label: t('library.installHistory') },
  ];

  const libraryLoading = library.loading && !library.library;
  const libraryFailed = Boolean(library.error) && !library.library;
  const installedPending = installedLoading && !installed.length;
  const installedFailed = Boolean(installedError) && !installed.length;

  return (
    <div className="page-shell space-y-6">
      <PageHeader
        title={t('library.title')}
        subtitle={t('library.subtitle')}
        actions={
          <Link to="/store/browse" className="btn btn-primary">
            {t('library.browseStore')}
          </Link>
        }
      />

      <Tabs value={tab} onChange={setTab} label={t('library.title')}
        items={TABS.map(item => ({ ...item, label: <>{item.label}
          {item.id === 'installed' && updates.length > 0 && <span
            className="rounded-full bg-warning/15 px-1.5 text-xs text-warning"
            title={t('library.updatesCount', { n: updates.length })}>{updates.length}</span>}
        </> }))}>
      {tab === 'installed' && installedPending ? (
        <LoadingGrid />
      ) : tab === 'installed' && installedFailed ? (
        <ErrorState
          message={installedError ?? undefined}
          onRetry={() => void reload()}
        />
      ) : tab !== 'installed' && libraryLoading ? (
        <LoadingGrid />
      ) : tab !== 'installed' && libraryFailed ? (
        <ErrorState
          message={library.error ?? undefined}
          onRetry={() => void library.load()}
        />
      ) : (
        <>
          {/* Filter bar: shared search + type filter; "updates only" pins to the installed tab. */}
          {tab !== 'entitlements' && tab !== 'history' && (
            <div className="flex flex-wrap items-center gap-2">
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t('library.searchPlaceholder')}
                className="input max-w-xs"
                aria-label={t('library.searchPlaceholder')}
              />
              {types.length > 0 && (
                <div className="w-40">
                  <SelectMenu
                    value={typeFilter}
                    options={[
                      { value: 'ALL', label: t('library.typeAll') },
                      ...types.map((type) => ({
                        value: type,
                        label: t(`type.${type}`),
                      })),
                    ]}
                    ariaLabel={t('common.type')}
                    onValueChange={(v) => setTypeFilter(String(v))}
                  />
                </div>
              )}
              {tab === 'installed' && (
                <label className="tap-compact flex cursor-pointer items-center gap-2 text-sm text-muted">
                  <input
                    type="checkbox"
                    checked={updatesOnly}
                    onChange={(e) => setUpdatesOnly(e.target.checked)}
                    className="size-4 accent-[var(--color-accent)]"
                  />
                  {t('library.updatesOnly')}
                  {updates.length > 0 && (
                    <span className="text-warning">({updates.length})</span>
                  )}
                </label>
              )}
            </div>
          )}

          {tab === 'installed' && installedError && installed.length > 0 && (
            <p className="alert alert-error" role="alert">
              {t('library.refreshFailed')}
              <button
                type="button"
                className="btn btn-secondary btn-sm tap-compact ml-2"
                onClick={() => void reload()}
              >
                {t('common.retry')}
              </button>
            </p>
          )}
          {tab !== 'installed' && library.error && library.library && (
            <p className="alert alert-error" role="alert">
              {t('library.refreshFailed')}
              <button
                type="button"
                className="btn btn-secondary btn-sm tap-compact ml-2"
                onClick={() => void library.load()}
              >
                {t('common.retry')}
              </button>
            </p>
          )}

          {tab === 'installed' &&
            (!installed.length ? (
              <EmptyState
                title={t('library.noInstalled')}
                hint={t('library.noInstalledHint')}
              >
                <Link
                  to="/store/browse"
                  className="btn btn-primary btn-sm tap-compact"
                >
                  {t('library.browseStore')}
                </Link>
              </EmptyState>
            ) : (
              <div className="space-y-3">
                {/* A quiet line replaces the old all-tab-sized "no updates" panel. */}
                {!updates.length && (
                  <p className="flex items-center gap-2 text-sm text-muted">
                    <svg
                      viewBox="0 0 24 24"
                      width="16"
                      height="16"
                      fill="none"
                      aria-hidden="true"
                      className="text-success"
                    >
                      <path
                        d="M5 13l4 4L19 7"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                    {t('library.noUpdates')}
                  </p>
                )}
                <ul className="card divide-y divide-line">
                  {filteredInstalled.map((item) => (
                    <li
                      key={item.coordinate}
                      className="flex flex-wrap items-center gap-3 px-4 py-3"
                    >
                      {/* Icon well: consistent letter tile so every row reads the same. */}
                      <span
                        className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-muted text-sm font-semibold text-muted"
                        aria-hidden="true"
                      >
                        {displayName(
                          item.coordinate,
                          namesByCoordinate.get(item.coordinate ?? ''),
                        )
                          .charAt(0)
                          .toUpperCase()}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Link
                            to={coordinateRoute(item.coordinate)}
                            className="font-medium hover:text-accent"
                          >
                            {displayName(
                              item.coordinate,
                              namesByCoordinate.get(item.coordinate ?? ''),
                            )}
                          </Link>
                          {item.updateAvailable && (
                            <Badge
                              variant="outline"
                              className={cn(
                                badgeBaseClass,
                                badgeToneClass.gold,
                              )}
                            >
                              {t('library.updates')}
                            </Badge>
                          )}
                        </div>
                        {item.coordinate && (
                          <CopyCoordinate value={item.coordinate} />
                        )}
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        {item.type && (
                          <Badge
                            variant="outline"
                            className={cn(badgeBaseClass, badgeToneClass.muted)}
                          >
                            {t(`type.${item.type}`)}
                          </Badge>
                        )}
                        {item.updateAvailable ? (
                          <Badge
                            variant="outline"
                            className={cn(badgeBaseClass, badgeToneClass.muted)}
                          >
                            {item.version} → {item.latestVersion}
                          </Badge>
                        ) : (
                          item.version && (
                            <span className="text-xs text-muted">
                              v{item.version}
                            </span>
                          )
                        )}
                        {item.updateAvailable && (
                          <Link
                            to={coordinateRoute(item.coordinate)}
                            className="btn btn-secondary btn-sm tap-compact"
                          >
                            {t('library.viewDetail')}
                          </Link>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
                {!filteredInstalled.length && (
                  <div className="py-6 text-center text-sm text-muted">
                    <p>{t('library.noMatch')}</p>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm tap-compact mt-3"
                      onClick={clearFilters}
                    >
                      {t('library.clearFilters')}
                    </button>
                  </div>
                )}
              </div>
            ))}

          {tab === 'favorites' &&
            (!library.library?.favorites?.length ? (
              <EmptyState
                title={t('library.noFavorites')}
                hint={t('library.noFavoritesHint')}
              >
                <Link
                  to="/store/browse"
                  className="btn btn-primary btn-sm tap-compact"
                >
                  {t('library.browseStore')}
                </Link>
              </EmptyState>
            ) : (
              <ul className="card divide-y divide-line">
                {filteredFavorites.map((favorite) => (
                  <li
                    key={favorite.listingCoordinate ?? favorite.name}
                    className="flex flex-wrap items-center gap-3 px-4 py-3"
                  >
                    <span
                      className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-muted text-sm font-semibold text-muted"
                      aria-hidden="true"
                    >
                      {(favorite.name ?? '?').charAt(0).toUpperCase()}
                    </span>
                    <div className="min-w-0 flex-1">
                      <Link
                        to={coordinateRoute(favorite.listingCoordinate)}
                        className="font-medium hover:text-accent"
                      >
                        {displayName(favorite.listingCoordinate, favorite.name)}
                      </Link>
                      {favorite.listingCoordinate && (
                        <CopyCoordinate value={favorite.listingCoordinate} />
                      )}
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      {favorite.type && (
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.muted)}
                        >
                          {t(`type.${favorite.type}`)}
                        </Badge>
                      )}
                      {favorite.latestVersion && (
                        <span className="text-xs text-muted">
                          v{favorite.latestVersion}
                        </span>
                      )}
                      {favorite.addedAt && (
                        <span className="text-xs text-muted">
                          {t('library.addedAt')}: {formatDate(favorite.addedAt)}
                        </span>
                      )}
                    </div>
                  </li>
                ))}
                {!filteredFavorites.length && (
                  <li className="py-6 text-center text-sm text-muted">
                    <p>{t('library.noMatch')}</p>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm tap-compact mt-3"
                      onClick={clearFilters}
                    >
                      {t('library.clearFilters')}
                    </button>
                  </li>
                )}
              </ul>
            ))}

          {tab === 'entitlements' &&
            (!library.library?.entitlements?.length ? (
              <EmptyState title={t('library.noEntitlements')} />
            ) : (
              <ul className="card divide-y divide-line">
                {(library.library.entitlements ?? []).map((entitlement) => (
                  <li
                    key={entitlement.listingCoordinate}
                    className="flex flex-wrap items-center gap-3 px-4 py-3"
                  >
                    <div className="min-w-0 flex-1">
                      <Link
                        to={coordinateRoute(entitlement.listingCoordinate)}
                        className="font-medium hover:text-accent"
                      >
                        {displayName(entitlement.listingCoordinate)}
                      </Link>
                      {entitlement.listingCoordinate && (
                        <CopyCoordinate value={entitlement.listingCoordinate} />
                      )}
                    </div>
                    {entitlement.free && (
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.success)}
                      >
                        {t('library.free')}
                      </Badge>
                    )}
                    {entitlement.acquiredAt && (
                      <span className="text-xs text-muted">
                        {formatDate(entitlement.acquiredAt)}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            ))}

          {tab === 'history' &&
            (!library.library?.installHistory?.length ? (
              <EmptyState title={t('library.noHistory')} />
            ) : (
              <div className="table-card">
                <table>
                  <thead>
                    <tr>
                      <th>{t('listing.version')}</th>
                      <th>{t('library.action')}</th>
                      <th>{t('library.outcome')}</th>
                      <th>{t('library.when')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {library.library.installHistory.map((event) => (
                      <tr key={event.idempotencyKey}>
                        <td>
                          {displayName(event.coordinate)}{' '}
                          <span className="text-xs text-muted">
                            v{event.version}
                          </span>
                        </td>
                        <td>
                          {t(`library.action_${event.action}`, {
                            defaultValue: event.action,
                          })}
                        </td>
                        <td>
                          {t(`library.outcome_${event.outcome}`, {
                            defaultValue: event.outcome,
                          })}
                        </td>
                        <td className="text-muted">
                          {formatDateTime(event.occurredAt)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
        </>
      )}
      </Tabs>
    </div>
  );
}
