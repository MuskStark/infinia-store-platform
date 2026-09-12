import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import {
  api,
  ApiRequestError,
  type DownloadTicket,
  type Library,
  type ListingDetail,
  type RatingsPage,
  type ResolveResponse,
} from '../api/client';
import MagicCard from '../components/MagicCard';
import { BlurFade } from '../components/magicui/blur-fade';
import { Badge } from '@/components/ui/badge';
import ProgressBar from '../components/ProgressBar';
import StateChip from '../components/StateChip';
import BeeLevelBadge from '../components/BeeLevelBadge';
import ErrorState from '../components/ErrorState';
import LoadingGrid from '../components/LoadingGrid';
import { formatDate, formatNumber } from '../utils/format';
import { useAuth } from '../stores/auth';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

type InstallStage =
  | 'idle'
  | 'resolving'
  | 'confirm'
  | 'downloading'
  | 'verifying'
  | 'done'
  | 'failed';
type TabKey =
  | 'overview'
  | 'versions'
  | 'permissions'
  | 'dependencies'
  | 'compatibility'
  | 'security'
  | 'reviews';
const TABS: TabKey[] = [
  'overview',
  'versions',
  'permissions',
  'dependencies',
  'compatibility',
  'security',
  'reviews',
];

/**
 * Listing detail with the install state machine (design §12.6):
 * 未安装 → 解析中 → 等待确认 → 下载中 → 校验中 → 已安装, with rollback display.
 * The web store acquires download tickets; host-embedded installs run the same
 * states through the local orchestrator.
 */
export default function ListingDetailView() {
  const { t, i18n } = useTranslation();
  const { namespace = '', slug = '' } = useParams();
  const location = useLocation();
  const auth = useAuth();

  const [detail, setDetail] = useState<ListingDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  /** Set when the listing is Infinia Level gated and the viewer ranks below it. */
  const [gateRequired, setGateRequired] = useState<number | null>(null);
  const [tab, setTab] = useState<TabKey>('overview');

  /** Resolve → confirm (permission aware) → download the offline install
   * package (design §9.2, §12.6). The web store cannot install into the host
   * itself — confirming hands the user the same installable package the host's
   * local install mode consumes. */
  const [installStage, setInstallStage] = useState<InstallStage>('idle');
  const [resolution, setResolution] = useState<ResolveResponse | null>(null);
  /** Which step failed — the message must not blame resolution for a download error. */
  const [failureKind, setFailureKind] = useState<'resolve' | 'download'>(
    'resolve',
  );
  const [favorited, setFavorited] = useState(false);

  const [ratings, setRatings] = useState<RatingsPage | null>(null);
  const [myStars, setMyStars] = useState(0);
  const [myComment, setMyComment] = useState('');
  const [ratingSaved, setRatingSaved] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [reportReason, setReportReason] = useState('malware');
  const [reportDetails, setReportDetails] = useState('');
  const [reportDone, setReportDone] = useState(false);
  const [reportError, setReportError] = useState<string | null>(null);

  const latestRelease = detail?.releases?.[0] ?? null;
  const upstream = detail?.upstream ?? null;
  const isUpstreamAggregated = upstream?.deliveryMode === 'MATERIALIZED_BLOB';

  /** Locale-aware localization (zh-CN matches zh first, then en, then anything). */
  const localization = useMemo(() => {
    const locs = detail?.localizations ?? [];
    const lang = i18n.language.toLowerCase();
    const primary = lang.split('-')[0];
    return (
      locs.find((l) => l.locale?.toLowerCase() === lang) ??
      locs.find((l) => l.locale?.toLowerCase().split('-')[0] === primary) ??
      locs.find((l) => l.locale?.toLowerCase().startsWith('en')) ??
      locs[0] ??
      null
    );
  }, [detail, i18n.language]);
  const displayName = localization?.name ?? detail?.slug ?? '';
  // Plain-text overview: a leading "# Title" markdown heading would render its
  // raw "#" here, so drop it when it merely repeats the product name.
  const overviewText = (() => {
    const raw =
      localization?.descriptionMarkdown?.trim() ||
      localization?.summary?.trim() ||
      t('listing.descriptionUnavailable');
    const heading = raw.match(/^#\s+(.+?)\s*(?:\n|$)/);
    if (
      heading &&
      heading[1].trim().toLowerCase() === displayName.trim().toLowerCase()
    ) {
      return raw.slice(heading[0].length).trim() || raw;
    }
    return raw;
  })();
  const sourceUrl = upstream?.sourceUrl ?? latestRelease?.sourceUrl;

  function displayVersion(version?: string | null): string {
    const declared = upstream?.upstreamVersion?.trim();
    if (declared) return `v${declared}`;
    if (!version || (isUpstreamAggregated && version === '0.0.0')) {
      return t('listing.versionUnspecified');
    }
    return `v${version}`;
  }

  function shortSha(value?: string | null): string {
    if (!value) return '—';
    return value.length > 16 ? `${value.slice(0, 12)}…` : value;
  }

  function formatSize(bytes?: number): string {
    if (!bytes && bytes !== 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB'];
    let value = bytes;
    let unit = 'B';
    for (const next of units) {
      if (value < 1024) break;
      value /= 1024;
      unit = next;
    }
    return `${value.toFixed(1)} ${unit}`;
  }

  /** Type-specific install behavior surfaced from the install manifest contract (plan §7.1). */
  const installInfo = (() => {
    switch (detail?.type) {
      case 'SKILL':
        return {
          mode: t('listing.installModeSkill'),
          hint: t('listing.installHintSkill'),
        };
      case 'MCP':
        return {
          mode: t('listing.installModeMcp'),
          hint: t('listing.installHintMcp'),
        };
      case 'PLUGIN':
        return {
          mode: t('listing.installModePlugin'),
          hint: t('listing.installHintPlugin'),
        };
      default:
        return null;
    }
  })();

  /**
   * Publisher-side Infinia Level gate management — moved out of the Publishing
   * Center so that page stays a focused release wizard. Visible to publisher
   * roles while viewing a listing; the server still rejects non-owners.
   */
  const canManageGate =
    auth.isAuthenticated &&
    ['PUBLISHER', 'ORG_ADMIN', 'PLATFORM_ADMIN'].some((role) =>
      auth.roles.includes(role),
    );
  const [gateLevel, setGateLevel] = useState(0);
  const [gateBusy, setGateBusy] = useState(false);
  const [gateSaved, setGateSaved] = useState(false);
  const [gateError, setGateError] = useState<string | null>(null);

  async function applyGate() {
    if (!detail) return;
    setGateBusy(true);
    setGateSaved(false);
    setGateError(null);
    try {
      await api.post(
        `/api/v1/publisher/listings/${detail.listingId}/min-bee-level`,
        { minBeeLevel: gateLevel },
      );
      // Keep the header badge in sync with the saved gate.
      setDetail({ ...detail, minBeeLevel: gateLevel });
      setGateSaved(true);
    } catch (e) {
      setGateError(e instanceof Error ? e.message : 'error');
    } finally {
      setGateBusy(false);
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    setGateRequired(null);
    try {
      const loaded = await api.get<ListingDetail>(
        `/api/v1/listings/${namespace}/${slug}`,
      );
      setDetail(loaded);
      setGateLevel(loaded?.minBeeLevel ?? 0);
      setGateSaved(false);
      setGateError(null);
      const ratingsPage = await api.get<RatingsPage>(
        `/api/v1/listings/${namespace}/${slug}/ratings`,
      );
      setRatings(ratingsPage);
      if (auth.isAuthenticated && loaded?.coordinate) {
        // Reflect the real favorite state instead of always defaulting to "not favorited".
        const library = await api.get<Library>('/api/v1/me/library');
        setFavorited(
          (library.favorites ?? []).some(
            (f) => f.listingCoordinate === loaded.coordinate,
          ),
        );
      }
    } catch (e) {
      if (e instanceof ApiRequestError && e.code === 'bee_level_required') {
        setGateRequired(Number(e.parameters?.requiredBeeLevel ?? 1));
      } else {
        setError(e instanceof Error ? e.message : 'error');
      }
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [namespace, slug]);

  async function submitRating(event: React.FormEvent) {
    event.preventDefault();
    if (!myStars) return;
    await api.put(`/api/v1/listings/${namespace}/${slug}/ratings`, {
      stars: myStars,
      comment: myComment || undefined,
    });
    setRatingSaved(true);
    setRatings(
      await api.get<RatingsPage>(
        `/api/v1/listings/${namespace}/${slug}/ratings`,
      ),
    );
  }

  async function submitReport(event: React.FormEvent) {
    event.preventDefault();
    setReportError(null);
    try {
      await api.post('/api/v1/reports', {
        coordinate: detail?.coordinate,
        reason: reportReason,
        details: reportDetails || undefined,
      });
      setReportDone(true);
      setReporting(false);
    } catch (e) {
      setReportError(e instanceof Error ? e.message : 'error');
    }
  }

  /** Resolve → confirm (permission aware) → ticket → verify hash (design §9.2, §12.6). */
  async function startInstall() {
    if (!latestRelease) return;
    setInstallStage('resolving');
    try {
      const resolveResponse = await api.post<ResolveResponse>(
        '/api/v1/resolutions',
        {
          coordinate: `infinia://${detail?.type?.toLowerCase()}/${namespace}/${slug}`,
          client: {
            hostVersion: '4.1.0',
            os: navigator.platform.toLowerCase().includes('mac')
              ? 'macos'
              : 'windows',
            arch: 'arm64',
            channel: 'stable',
            installed: [],
          },
        },
      );
      setResolution(resolveResponse);
      if (!resolveResponse.resolvable) {
        setFailureKind('resolve');
        setInstallStage('failed');
        return;
      }
      setInstallStage('confirm');
    } catch {
      setFailureKind('resolve');
      setInstallStage('failed');
    }
  }

  async function confirmInstall() {
    if (!latestRelease) return;
    setInstallStage('downloading');
    try {
      // Confirming the permission-aware plan downloads the offline install
      // package — the bytes the host's local install mode imports.
      await api.download(
        `/api/v1/releases/${latestRelease.releaseId}/install-package`,
        `${namespace}.${slug}.zip`,
      );
      setInstallStage('verifying');
      // Web store verifies metadata; the host performs byte-level SHA-256 + signature.
      await new Promise((resolve) => setTimeout(resolve, 600));
      setInstallStage('done');
    } catch {
      setFailureKind('download');
      setInstallStage('failed');
    }
  }

  /**
   * APP listings distribute installed + portable binaries per platform; instead of
   * the resolve→confirm install machine they download a concrete artifact, addressed
   * by artifactId so installer and portable variants of one release stay distinct.
   */
  const isAppListing = detail?.type === 'APP';
  const [artifactTickets, setArtifactTickets] = useState<
    Record<string, DownloadTicket | 'loading' | 'error'>
  >({});

  function detectOs(): string {
    const platform = (navigator.platform || '').toLowerCase();
    if (platform.includes('win')) return 'windows';
    if (platform.includes('mac')) return 'macos';
    if (platform.includes('linux')) return 'linux';
    return 'universal';
  }

  function detectArch(): string {
    const ua = navigator.userAgent.toLowerCase();
    if (ua.includes('arm64') || ua.includes('aarch64')) return 'arm64';
    if (ua.includes('x86_64') || ua.includes('amd64') || ua.includes('wow64'))
      return 'x64';
    return 'universal';
  }

  /** Best binary for this browser: own platform first, universal fallback, installer preferred. */
  const recommendedArtifact = (() => {
    const artifacts = latestRelease?.artifacts ?? [];
    if (!artifacts.length) return null;
    const os = detectOs();
    const arch = detectArch();
    const score = (a: (typeof artifacts)[number]) =>
      (a.platform === os ? 0 : a.platform === 'universal' ? 1 : 9) +
      (a.arch === arch ? 0 : a.arch === 'universal' ? 1 : 9) +
      (a.kind === 'INSTALLER' ? 0 : 1) +
      (a.variant === 'lite' ? 0 : a.variant === 'jre' ? 1 : 2);
    return [...artifacts].sort((x, y) => score(x) - score(y))[0] ?? null;
  })();

  async function downloadArtifact(artifact: {
    artifactId?: string | null;
    filename: string;
  }) {
    if (!latestRelease || !artifact.artifactId) return;
    const key = artifact.artifactId;
    if (artifactTickets[key] === 'loading') return;
    setArtifactTickets((current) => ({ ...current, [key]: 'loading' }));
    try {
      const ticket = await api.post<DownloadTicket>(
        `/api/v1/releases/${latestRelease.releaseId}/download-ticket?artifactId=${key}`,
      );
      setArtifactTickets((current) => ({ ...current, [key]: ticket }));
      // Ticket URLs are short-lived and server-relative: hand the bytes to the
      // browser immediately via a synthetic anchor click.
      const anchor = document.createElement('a');
      anchor.href = ticket.url;
      anchor.download = artifact.filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
    } catch {
      setArtifactTickets((current) => ({ ...current, [key]: 'error' }));
    }
  }

  /**
   * Direct download of the offline install package (plan §7.1): Native install
   * manifest + signed artifact + checksums in one ZIP. The saved file installs
   * through the host's local install mode — 主程序本地安装 — without a store
   * connection. Tracked per release so the versions tab can offer old versions.
   */
  const [packageDownloads, setPackageDownloads] = useState<
    Record<string, 'loading' | 'error'>
  >({});

  async function downloadInstallPackage(releaseId?: string) {
    const id = releaseId ?? latestRelease?.releaseId;
    // 'error' must stay retryable — only an in-flight download blocks re-entry.
    if (!id || packageDownloads[id] === 'loading') return;
    setPackageDownloads((current) => ({ ...current, [id]: 'loading' }));
    try {
      await api.download(
        `/api/v1/releases/${id}/install-package`,
        `${namespace}.${slug}.zip`,
      );
      setPackageDownloads((current) => {
        const next = { ...current };
        delete next[id];
        return next;
      });
    } catch {
      setPackageDownloads((current) => ({ ...current, [id]: 'error' }));
    }
  }

  async function toggleFavorite() {
    if (!detail) return;
    // listingId is the coordinate-derived key; the API accepts the UUID from /me/library.
    await api.put(`/api/v1/me/favorites/${detail.listingId}`);
    setFavorited(!favorited);
  }

  const installLabel = (() => {
    switch (installStage) {
      case 'resolving':
        return t('listing.resolving');
      case 'confirm':
        return t('listing.awaitingConfirm');
      case 'downloading':
        return t('listing.downloading');
      case 'verifying':
        return t('listing.verifying');
      case 'done':
        return t('listing.installed');
      case 'failed':
        return t('listing.manualAction');
      default:
        return t('common.get');
    }
  })();

  return (
    <div className="page-shell">
      {error ? (
        <ErrorState message={error} onRetry={() => void load()} />
      ) : loading ? (
        <LoadingGrid />
      ) : gateRequired !== null ? (
        <div className="rounded-xl border border-line bg-surface p-10 text-center">
          <div className="text-5xl">🐝</div>
          <h1 className="mt-4 text-2xl font-bold">
            {t('listing.beeGateTitle')}
          </h1>
          <p className="mx-auto mt-3 max-w-md text-sm text-muted">
            {t('listing.beeGateBody')}
          </p>
          <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
            <BeeLevelBadge level={gateRequired} demands />
            {!auth.isAuthenticated && (
              <Link to="/store/signin" className="btn btn-primary">
                {t('listing.beeGateSignIn')}
              </Link>
            )}
          </div>
        </div>
      ) : detail ? (
        <div className="space-y-6">
          {/* Marketplace detail header: big black title, badge row, publisher line;
        the CTA rail sits at the right like the marketplace install column. */}
          <BlurFade>
            <header className="space-y-4">
              <div className="flex flex-wrap items-start justify-between gap-6">
                <div className="min-w-0 flex-1">
                  <h1 className="max-w-2xl text-3xl font-bold leading-tight tracking-tight">
                    {displayName}
                  </h1>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <Badge
                      variant="outline"
                      className={cn(badgeBaseClass, badgeToneClass.muted)}
                    >
                      {t(`type.${detail.type}`)}
                    </Badge>
                    {latestRelease && (
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.muted)}
                      >
                        {displayVersion(latestRelease.version)}
                      </Badge>
                    )}
                    {isUpstreamAggregated && (
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.accent)}
                      >
                        {t('listing.aggregatedUpstream')}
                      </Badge>
                    )}
                    {detail.minBeeLevel != null && detail.minBeeLevel > 0 && (
                      <BeeLevelBadge level={detail.minBeeLevel} demands />
                    )}
                    {detail.defaultChannel !== 'stable' && (
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.accent)}
                      >
                        {t(`channel.${detail.defaultChannel}`)}
                      </Badge>
                    )}
                  </div>
                  <div className="mt-3 flex items-center gap-2.5">
                    <span
                      className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-xs font-bold text-white"
                      style={{ background: 'var(--hero-gradient)' }}
                      aria-hidden="true"
                    >
                      {(detail.publisherName ?? '?').charAt(0).toUpperCase()}
                    </span>
                    <span className="text-sm font-semibold">
                      {detail.publisherName}
                    </span>
                  </div>
                  <p className="mt-3 max-w-2xl text-[15px] leading-7 text-muted">
                    {localization?.summary}
                  </p>
                  <p className="mt-2 text-sm text-muted">
                    {formatNumber(detail.downloads ?? 0)}{' '}
                    {t('discover.statsDownloads')}
                    {' · '}
                    {formatNumber(detail.favorites ?? 0)}{' '}
                    {t('listing.favoritesCount')}
                    {' · '}
                    {t('listing.updated')}: {formatDate(detail.updatedAt)}
                  </p>
                  {detail.category || detail.tags?.length ? (
                    <div className="mt-3 flex flex-wrap items-center gap-1.5">
                      {detail.category && (
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.muted)}
                        >
                          {detail.category}
                        </Badge>
                      )}
                      {(detail.tags ?? []).map((tag) => (
                        <Badge
                          key={tag}
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.muted)}
                        >
                          {tag}
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                </div>
                <div className="flex w-full flex-col gap-2 sm:w-56">
                  {!isAppListing && (
                    <BlurFade>
                      <button
                        type="button"
                        className="btn btn-primary w-full"
                        disabled={
                          installStage !== 'idle' && installStage !== 'failed'
                        }
                        onClick={() => void startInstall()}
                      >
                        {installLabel}
                      </button>
                    </BlurFade>
                  )}
                  {isAppListing && recommendedArtifact && (
                    <button
                      type="button"
                      className="btn btn-primary w-full"
                      disabled={
                        artifactTickets[
                          recommendedArtifact.artifactId ?? ''
                        ] === 'loading'
                      }
                      onClick={() => void downloadArtifact(recommendedArtifact)}
                    >
                      {artifactTickets[recommendedArtifact.artifactId ?? ''] ===
                      'loading'
                        ? t('listing.downloading')
                        : t('common.download')}
                    </button>
                  )}
                  {installStage === 'done' && (
                    <p className="text-xs leading-5 text-success">
                      {t('listing.packageDownloaded')}
                    </p>
                  )}
                  {(installStage === 'downloading' ||
                    installStage === 'verifying') && <ProgressBar />}
                  {auth.isAuthenticated && (
                    <button
                      className="btn btn-secondary"
                      onClick={() => void toggleFavorite()}
                    >
                      {favorited
                        ? t('listing.favoriteRemove')
                        : t('listing.favoriteAdd')}
                    </button>
                  )}
                  {auth.isAuthenticated && !reportDone && (
                    <button
                      className="btn btn-ghost"
                      onClick={() => setReporting(true)}
                    >
                      {t('listing.report')}
                    </button>
                  )}
                </div>
              </div>

              {/* Permission confirmation step (design §9.3): escalate = ask again. */}
              {installStage === 'confirm' && resolution ? (
                <div className="mt-6 rounded-lg border border-accent/40 bg-accent/5 p-4">
                  <h2 className="font-semibold">
                    {t('listing.confirmInstall')}
                  </h2>
                  <ul className="mt-2 list-disc space-y-1 pl-6 text-sm">
                    {(resolution.plan ?? []).map((node) => (
                      <li key={node.coordinate}>
                        {node.coordinate}
                        {node.alreadyInstalled && (
                          <Badge
                            variant="outline"
                            className={cn(
                              badgeBaseClass,
                              badgeToneClass.success,
                            )}
                          >
                            {t('listing.installed')}
                          </Badge>
                        )}
                      </li>
                    ))}
                  </ul>
                  {Boolean(resolution.missing?.length) && (
                    <div className="mt-2 text-sm text-red-600">
                      {t('listing.missingDeps')}:
                      {` ${(resolution.missing ?? []).map((m) => m.coordinate).join(', ')}`}
                    </div>
                  )}
                  <div className="mt-3 flex gap-2">
                    <button
                      className="btn btn-primary"
                      onClick={() => void confirmInstall()}
                    >
                      {t('common.confirm')}
                    </button>
                    <button
                      className="btn btn-secondary"
                      onClick={() => setInstallStage('idle')}
                    >
                      {t('common.cancel')}
                    </button>
                  </div>
                </div>
              ) : installStage === 'failed' ? (
                <p className="mt-4 text-sm text-red-600">
                  {failureKind === 'download'
                    ? t('listing.installDownloadFailed')
                    : t('listing.resolveFailed')}
                </p>
              ) : null}
            </header>
          </BlurFade>

          <nav
            className="flex flex-wrap gap-1 border-b border-line"
            role="tablist"
          >
            {TABS.map((key) => (
              <button
                key={key}
                role="tab"
                aria-selected={tab === key}
                className={cn(
                  '-mb-px border-b-2 px-4 py-2.5 text-sm transition-colors',
                  tab === key
                    ? 'border-accent font-semibold text-accent'
                    : 'border-transparent text-muted hover:text-ink',
                )}
                onClick={() => setTab(key)}
              >
                {t(`listing.${key}`)}
              </button>
            ))}
          </nav>

          {tab === 'overview' && (
            <section className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
              <article className="max-w-none space-y-5">
                <div>
                  <h2 className="text-lg font-semibold">
                    {t('listing.aboutTitle')}
                  </h2>
                  <p className="mt-3 whitespace-pre-wrap leading-7 text-muted">
                    {overviewText}
                  </p>
                </div>
                {upstream && (
                  <div className="rounded-lg border border-line bg-surface-muted p-5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <h3 className="font-semibold">
                        {t('listing.upstreamMetadata')}
                      </h3>
                      {isUpstreamAggregated && (
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.success)}
                        >
                          {t('listing.storedDelivery')}
                        </Badge>
                      )}
                    </div>
                    {isUpstreamAggregated && (
                      <p className="mt-2 text-sm leading-6 text-muted">
                        {t('listing.storedDeliveryHint')}
                      </p>
                    )}
                    <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
                      <div>
                        <dt className="text-muted">
                          {t('listing.upstreamSource')}
                        </dt>
                        <dd className="mt-1 font-medium">
                          {upstream.sourceName || '—'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted">
                          {t('listing.sourcePath')}
                        </dt>
                        <dd className="mt-1 break-all font-mono text-xs">
                          {upstream.sourcePath || upstream.externalId || '—'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted">{t('listing.revision')}</dt>
                        <dd className="mt-1 font-mono text-xs">
                          {shortSha(upstream.commitSha || upstream.ref)}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted">
                          {t('listing.lastSynced')}
                        </dt>
                        <dd className="mt-1">
                          {formatDate(upstream.lastSeenAt)}
                        </dd>
                      </div>
                    </dl>
                  </div>
                )}
                {Boolean(detail.screenshots?.length) && (
                  <div className="mt-6 grid gap-3 sm:grid-cols-2">
                    {(detail.screenshots ?? []).map((shot) => (
                      <img
                        key={shot}
                        src={shot}
                        alt={displayName}
                        loading="lazy"
                        className="w-full rounded-lg border border-line object-cover"
                      />
                    ))}
                  </div>
                )}
              </article>
              <aside className="card space-y-3 self-start p-5 text-sm">
                <h2 className="font-semibold">{t('listing.infoTitle')}</h2>
                <dl className="space-y-2">
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.version')}
                    </dt>
                    <dd className="text-right">
                      {latestRelease ? (
                        <code>{displayVersion(latestRelease.version)}</code>
                      ) : (
                        <span>—</span>
                      )}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.category')}
                    </dt>
                    <dd className="text-right">{detail.category || '—'}</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.license')}
                    </dt>
                    <dd className="text-right">
                      {latestRelease?.license ? (
                        <>{latestRelease.license}</>
                      ) : (
                        <span>—</span>
                      )}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.publishedAt')}
                    </dt>
                    <dd className="text-right">
                      {formatDate(latestRelease?.publishedAt)}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.updated')}
                    </dt>
                    <dd className="text-right">
                      {formatDate(detail.updatedAt)}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="shrink-0 text-muted">
                      {t('listing.coordinate')}
                    </dt>
                    <dd className="text-right">
                      <code className="block break-all rounded-lg bg-surface-muted px-2 py-1 text-left text-xs">
                        {detail.coordinate}
                      </code>
                    </dd>
                  </div>
                </dl>
                {sourceUrl && (
                  <a
                    href={sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block font-medium text-accent underline"
                  >
                    {t('listing.source')} ↗
                  </a>
                )}
                {installInfo && (
                  <div className="rounded-xl bg-surface-muted p-3 text-xs">
                    <p className="font-semibold">
                      {t('listing.installBehavior')}: {installInfo.mode}
                    </p>
                    <p className="mt-1 text-muted">{installInfo.hint}</p>
                    {!isAppListing && latestRelease && (
                      <p className="mt-1 text-muted">
                        {t('listing.downloadPackageHint')}
                      </p>
                    )}
                  </div>
                )}
                {canManageGate && (
                  <div className="space-y-2 border-t border-line pt-3">
                    <h3 className="font-semibold">{t('publisher.setGate')}</h3>
                    <p className="text-xs text-muted">
                      {t('publisher.setGateHint')}
                    </p>
                    <select
                      value={gateLevel}
                      onChange={(e) => setGateLevel(Number(e.target.value))}
                      className="input"
                      aria-label={t('publisher.minBeeLevel')}
                    >
                      {[0, 1, 2, 3, 4].map((level) => (
                        <option key={level} value={level}>
                          {level === 0
                            ? t('publisher.beeLevelPublic')
                            : `${t(`beeLevel.${level}`)} · Lv${level}+`}
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm w-full"
                      disabled={gateBusy}
                      onClick={() => void applyGate()}
                    >
                      {t('common.confirm')}
                    </button>
                    {gateSaved && (
                      <p className="text-xs text-success" role="status">
                        {t('publisher.gateUpdated')}
                      </p>
                    )}
                    {gateError && (
                      <p className="text-xs text-danger">{gateError}</p>
                    )}
                  </div>
                )}
              </aside>
            </section>
          )}

          {tab === 'versions' && (
            <section className="space-y-3">
              {isUpstreamAggregated && !upstream?.upstreamVersion && (
                <div className="rounded-lg border border-accent/30 bg-accent/5 p-4 text-sm text-muted">
                  <p className="font-semibold text-fg dark:text-white">
                    {t('listing.versionUnspecified')}
                  </p>
                  <p className="mt-1">{t('listing.versionPlaceholderHint')}</p>
                </div>
              )}
              {(detail.releases ?? []).map((release) => (
                <MagicCard key={release.releaseId} className="rounded-lg p-5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold">
                        {displayVersion(release.version)}
                      </span>
                      <StateChip status={release.status} />
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.muted)}
                      >
                        {t(`channel.${release.channel}`)}
                      </Badge>
                      {(release.rolloutPercent ?? 100) < 100 && (
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.accent)}
                        >
                          {t('listing.rollout', {
                            percent: release.rolloutPercent,
                          })}
                        </Badge>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      {release.status === 'PUBLISHED' && !isAppListing && (
                        <button
                          className="rounded-lg border border-line px-3 py-1 text-xs font-medium hover:bg-surface-2 disabled:opacity-50"
                          disabled={
                            packageDownloads[release.releaseId] === 'loading'
                          }
                          onClick={() =>
                            void downloadInstallPackage(release.releaseId)
                          }
                        >
                          {packageDownloads[release.releaseId] === 'loading'
                            ? t('listing.downloading')
                            : t('listing.downloadPackage')}
                        </button>
                      )}
                      {packageDownloads[release.releaseId] === 'error' && (
                        <span className="text-xs text-red-500">
                          {t('listing.packageDownloadFailed')}
                        </span>
                      )}
                      <span className="text-xs text-muted">
                        {formatDate(release.publishedAt)}
                      </span>
                    </div>
                  </div>
                  {release.requiresHost && (
                    <p className="mt-2 text-sm text-muted">
                      {t('listing.requiresHost')}:{' '}
                      <code>{release.requiresHost}</code>
                    </p>
                  )}
                  {release.changelogMarkdown ? (
                    <p className="mt-2 whitespace-pre-wrap text-sm">
                      {release.changelogMarkdown}
                    </p>
                  ) : (
                    <p className="mt-2 text-sm text-muted">
                      {t('listing.noChangelog')}
                    </p>
                  )}
                  {upstream && (
                    <dl className="mt-4 grid gap-2 border-t border-line pt-3 text-xs sm:grid-cols-3">
                      <div>
                        <dt className="text-muted">
                          {t('listing.sourcePath')}
                        </dt>
                        <dd className="mt-1 break-all font-mono">
                          {upstream.sourcePath || upstream.externalId || '—'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted">{t('listing.revision')}</dt>
                        <dd className="mt-1 font-mono">
                          {shortSha(upstream.commitSha || upstream.ref)}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-muted">
                          {t('listing.lastSynced')}
                        </dt>
                        <dd className="mt-1">
                          {formatDate(upstream.lastSeenAt)}
                        </dd>
                      </div>
                    </dl>
                  )}
                </MagicCard>
              ))}
            </section>
          )}

          {tab === 'permissions' && (
            <section>
              {Boolean(latestRelease?.permissions?.length) ? (
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="text-muted">
                      <th className="p-2">{t('listing.permissionId')}</th>
                      <th className="p-2">{t('listing.scope')}</th>
                      <th className="p-2">{t('listing.required')}</th>
                      <th className="p-2">{t('listing.reason')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(latestRelease?.permissions ?? []).map((permission) => (
                      <tr
                        key={permission.permissionId}
                        className="border-t border-line"
                      >
                        <td className="p-2 font-mono text-xs">
                          {permission.permissionId}
                        </td>
                        <td className="p-2 font-mono text-xs">
                          {permission.scope}
                        </td>
                        <td className="p-2">
                          {permission.required === false
                            ? t('listing.optional')
                            : t('listing.required')}
                        </td>
                        <td className="p-2">{permission.reason || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="card p-5">
                  <h2 className="font-semibold">
                    {t('listing.noPermissionsDeclared')}
                  </h2>
                  <p className="mt-2 text-sm leading-6 text-muted">
                    {isUpstreamAggregated
                      ? t('listing.noPermissionsDeclaredUpstream')
                      : t('listing.noPermissionsDeclaredLocal')}
                  </p>
                  {isUpstreamAggregated && (
                    <p className="mt-3 rounded-xl bg-surface-muted p-3 text-sm">
                      {t('listing.permissionDownloadScan')}
                    </p>
                  )}
                </div>
              )}
            </section>
          )}

          {tab === 'dependencies' && (
            <section>
              {Boolean(latestRelease?.dependencies?.length) ? (
                <ul className="space-y-2">
                  {(latestRelease?.dependencies ?? []).map((dependency) => (
                    <li
                      key={dependency.coordinate}
                      className="card flex items-center gap-2 p-3 text-sm"
                    >
                      <code className="text-xs">{dependency.coordinate}</code>
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.muted)}
                      >
                        {dependency.range}
                      </Badge>
                      {!dependency.optional && (
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.danger)}
                        >
                          {t('listing.required')}
                        </Badge>
                      )}
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="card p-5">
                  <h2 className="font-semibold">
                    {t('listing.noDependenciesDeclared')}
                  </h2>
                  <p className="mt-2 text-sm leading-6 text-muted">
                    {isUpstreamAggregated
                      ? t('listing.noDependenciesDeclaredUpstream')
                      : t('listing.noDependenciesDeclaredLocal')}
                  </p>
                </div>
              )}
            </section>
          )}

          {tab === 'compatibility' && (
            <section className="space-y-3">
              <p className="text-sm text-muted">{t('listing.compatHint')}</p>
              {isUpstreamAggregated && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="card p-4">
                    <p className="text-xs text-muted">
                      {t('listing.deliveryMode')}
                    </p>
                    <p className="mt-1 font-semibold">
                      {t('listing.aggregatedUpstream')}
                    </p>
                    <p className="mt-1 text-sm text-muted">
                      {t('listing.storedDelivery')}
                    </p>
                  </div>
                  <div className="card p-4">
                    <p className="text-xs text-muted">
                      {t('listing.targetPlatform')}
                    </p>
                    <p className="mt-1 font-semibold">
                      {t('listing.targetPlatformUniversal')}
                    </p>
                    <p className="mt-1 text-sm text-muted">
                      {t('listing.noHostRestriction')}
                    </p>
                  </div>
                </div>
              )}
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="text-muted">
                    <th className="p-2">{t('listing.version')}</th>
                    <th className="p-2">{t('listing.requiresHost')}</th>
                    <th className="p-2">{t('listing.channel')}</th>
                    <th className="p-2">{t('listing.status')}</th>
                  </tr>
                </thead>
                <tbody>
                  {(detail.releases ?? []).map((release) => (
                    <tr
                      key={release.releaseId}
                      className="border-t border-line"
                    >
                      <td className="p-2 font-mono text-xs">
                        {displayVersion(release.version)}
                      </td>
                      <td className="p-2">
                        {release.requiresHost ? (
                          <code>{release.requiresHost}</code>
                        ) : (
                          <span className="text-muted">
                            {t('listing.noHostRestriction')}
                          </span>
                        )}
                      </td>
                      <td className="p-2">{t(`channel.${release.channel}`)}</td>
                      <td className="p-2">
                        <StateChip status={release.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          {tab === 'security' && (
            <section className="space-y-4 text-sm">
              {isUpstreamAggregated && (
                <div className="rounded-lg border border-accent/30 bg-accent/5 p-5">
                  <h2 className="font-semibold">
                    {t('listing.upstreamSecurityTitle')}
                  </h2>
                  <ol className="mt-3 grid gap-3 sm:grid-cols-2">
                    {[
                      t('listing.securitySyncFetch'),
                      t('listing.securityPackaging'),
                      t('listing.securityScan'),
                      t('listing.securityStoredSigned'),
                    ].map((item, index) => (
                      <li
                        key={item}
                        className="flex gap-3 rounded-xl bg-surface/70 p-3"
                      >
                        <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand text-xs font-bold text-[#18181b]">
                          {index + 1}
                        </span>
                        <span className="leading-6">{item}</span>
                      </li>
                    ))}
                  </ol>
                  <dl className="mt-4 grid gap-3 border-t border-accent/20 pt-4 sm:grid-cols-2">
                    <div>
                      <dt className="text-muted">
                        {t('listing.metadataDigest')}
                      </dt>
                      <dd className="mt-1 break-all font-mono text-xs">
                        {upstream?.metadataSha256 || '—'}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-muted">{t('listing.revision')}</dt>
                      <dd className="mt-1 font-mono text-xs">
                        {shortSha(upstream?.commitSha || upstream?.ref)}
                      </dd>
                    </div>
                  </dl>
                </div>
              )}
              {!latestRelease?.artifacts?.length ? (
                <p className="text-muted">{t('listing.noArtifacts')}</p>
              ) : (
                <>
                  {(latestRelease.artifacts ?? []).map((artifact) => (
                    <div
                      key={artifact.artifactId}
                      className="card space-y-1 p-4"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.muted)}
                        >
                          {artifact.kind}
                        </Badge>
                        {artifact.variant && artifact.variant !== 'default' && (
                          <Badge
                            variant="outline"
                            className={cn(
                              badgeBaseClass,
                              badgeToneClass.accent,
                            )}
                          >
                            {artifact.variant}
                          </Badge>
                        )}
                        <span className="font-mono text-xs">
                          {artifact.filename}
                        </span>
                        <span className="text-muted">
                          {artifact.platform}/{artifact.arch} ·{' '}
                          {isUpstreamAggregated && !artifact.size
                            ? t('listing.generatedOnDemand')
                            : formatSize(artifact.size)}
                        </span>
                        {artifact.artifactId && (
                          <button
                            className="ml-auto rounded-lg border border-line px-3 py-1 text-xs font-medium hover:bg-surface-2 disabled:opacity-50"
                            disabled={
                              artifactTickets[artifact.artifactId] === 'loading'
                            }
                            onClick={() => void downloadArtifact(artifact)}
                          >
                            {artifactTickets[artifact.artifactId] === 'loading'
                              ? t('listing.downloading')
                              : t('common.download')}
                          </button>
                        )}
                      </div>
                      {artifactTickets[artifact.artifactId ?? ''] ===
                        'error' && (
                        <p className="text-xs text-red-500">
                          {t('listing.downloadFailed')}
                        </p>
                      )}
                      <p className="break-all">
                        <span className="text-muted">
                          {t('listing.sha256')}:
                        </span>
                        {` `}
                        <code>
                          {artifact.sha256 ||
                            (isUpstreamAggregated
                              ? t('listing.checksumAtDownload')
                              : '—')}
                        </code>
                      </p>
                      {artifact.keyId && (
                        <p className="break-all">
                          <span className="text-muted">
                            {t('listing.signature')}:
                          </span>
                          {` `}
                          <code>{artifact.keyId}</code>
                        </p>
                      )}
                    </div>
                  ))}
                  {!isUpstreamAggregated ? (
                    <p className="text-muted">{t('listing.signatureNote')}</p>
                  ) : (
                    <p className="text-muted">
                      {t('listing.storedChecksumNote')}
                    </p>
                  )}
                </>
              )}
            </section>
          )}

          {tab === 'reviews' && (
            <section className="space-y-6">
              {ratings && (
                <div className="flex flex-wrap items-center gap-4">
                  <div className="text-4xl font-bold">
                    {ratings.summary?.average?.toFixed(1) ?? '—'}
                  </div>
                  <div className="text-sm text-muted">
                    {t('listing.ratingCount', {
                      count: ratings.summary?.count ?? 0,
                    })}
                  </div>
                </div>
              )}

              <ul className="space-y-2">
                {(ratings?.ratings ?? []).map((rating) => (
                  <li key={rating.ratingId} className="card p-3 text-sm">
                    <div
                      className="flex items-center gap-1"
                      aria-label={String(rating.stars)}
                    >
                      {[1, 2, 3, 4, 5].map((n) => (
                        <span
                          key={n}
                          className={
                            n <= (rating.stars ?? 0)
                              ? 'text-amber-500'
                              : 'text-muted'
                          }
                        >
                          ★
                        </span>
                      ))}
                    </div>
                    {rating.comment && <p className="mt-1">{rating.comment}</p>}
                  </li>
                ))}
                {!ratings?.ratings?.length && (
                  <li className="text-sm text-muted">
                    {t('listing.noReviews')}
                  </li>
                )}
              </ul>

              {auth.isAuthenticated ? (
                <form className="card space-y-3 p-4" onSubmit={submitRating}>
                  <h3 className="font-semibold">{t('listing.writeReview')}</h3>
                  <div
                    className="flex gap-1"
                    role="radiogroup"
                    aria-label={t('listing.stars')}
                  >
                    {[1, 2, 3, 4, 5].map((n) => (
                      <button
                        key={n}
                        type="button"
                        role="radio"
                        aria-checked={myStars === n}
                        className={cn(
                          'text-2xl leading-none',
                          n <= myStars ? 'text-amber-500' : 'text-muted',
                        )}
                        onClick={() => setMyStars(n)}
                      >
                        ★
                      </button>
                    ))}
                  </div>
                  <textarea
                    value={myComment}
                    onChange={(e) => setMyComment(e.target.value)}
                    rows={3}
                    maxLength={2000}
                    placeholder={t('listing.reviewPlaceholder')}
                    className="input"
                  />
                  <div className="flex items-center gap-2">
                    <button
                      disabled={!myStars}
                      className="btn btn-primary shrink-0 whitespace-nowrap"
                    >
                      {t('listing.submitReview')}
                    </button>
                    {ratingSaved && (
                      <span className="text-sm text-success">
                        {t('listing.reviewSaved')}
                      </span>
                    )}
                  </div>
                </form>
              ) : (
                <p className="text-sm text-muted">
                  <Link
                    className="text-accent hover:underline"
                    to={`/store/signin?redirect=${encodeURIComponent(location.pathname + location.search)}`}
                  >
                    {t('listing.signInToReview')}
                  </Link>
                </p>
              )}
            </section>
          )}
        </div>
      ) : null}

      {/* Abuse report dialog (design §12.4 举报) */}
      {reporting && (
        <div
          className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4"
          role="dialog"
          aria-label={t('listing.report')}
          onClick={(e) => {
            if (e.target === e.currentTarget) setReporting(false);
          }}
        >
          <div className="w-full max-w-md rounded-lg border border-line bg-surface p-6">
            <h2 className="text-lg font-bold">{t('listing.report')}</h2>
            {reportDone ? (
              <p className="alert alert-success mt-4" role="status">
                {t('listing.reportDone')}
              </p>
            ) : (
              <form className="mt-4 space-y-3" onSubmit={submitReport}>
                <label className="block text-sm">
                  {t('listing.reportReason')}
                  <select
                    value={reportReason}
                    onChange={(e) => setReportReason(e.target.value)}
                    className="input mt-1"
                  >
                    {[
                      'malware',
                      'policy_violation',
                      'spam',
                      'misleading',
                      'license',
                      'other',
                    ].map((reason) => (
                      <option key={reason} value={reason}>
                        {t(`admin.reason.${reason}`)}
                      </option>
                    ))}
                  </select>
                </label>
                <textarea
                  value={reportDetails}
                  onChange={(e) => setReportDetails(e.target.value)}
                  rows={3}
                  maxLength={2000}
                  placeholder={t('listing.reportDetails')}
                  className="input"
                />
                {reportError && (
                  <p className="text-sm text-danger">{reportError}</p>
                )}
                <div className="flex justify-end gap-2">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => setReporting(false)}
                  >
                    {t('common.cancel')}
                  </button>
                  <button className="btn btn-danger">
                    {t('listing.reportSubmit')}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
