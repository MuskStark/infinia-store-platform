import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import {
  api,
  type Library,
  type MembershipStatus,
  type PublicUser,
} from '../api/client';
import { BlurFade } from '../components/magicui/blur-fade';
import HexWash from '../components/HexWash';
import { BorderBeam } from '../components/magicui/border-beam';
import { Badge } from '@/components/ui/badge';
import BeeCrest from '../components/BeeCrest';
import HexCluster from '../components/HexCluster';
import EmptyState from '../components/EmptyState';
import ErrorState from '../components/ErrorState';
import LoadingGrid from '../components/LoadingGrid';
import PageHeader from '../components/PageHeader';
import { formatDate, formatDateTime } from '../utils/format';
import { useAuth, patchUser } from '../stores/auth';
import { beeMark } from '../bee-levels';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

type SessionRow = {
  sessionId: string;
  clientId: string;
  kind: string;
  createdAt: string;
};
type DeviceRow = {
  deviceId: string;
  name: string;
  platform: string;
  revoked: boolean;
};

/**
 * User Center (用户中心): the account-management page — identity, the current
 * Infinia Level with its membership deadline and renew action in one line,
 * account details (display name + password) and sign-in sessions & devices.
 *
 * It deliberately does NOT mirror the global navigation: 我的库 and 组织 have
 * their own destinations (library in the header nav, organizations in the
 * account menu), and the full bee ladder lives on /membership. Information
 * appears exactly once.
 *
 * The hero is the page's signature ("hive passport"): hexagon identity mark
 * and a display-size level statement tinted by the tier.
 */
export default function AccountView() {
  const { t } = useTranslation();
  const auth = useAuth();

  const [user, setUser] = useState<PublicUser | null>(null);
  const [membership, setMembership] = useState<MembershipStatus | null>(null);
  /** Artifacts the account holds entitlements for (我的库 · 已获取). */
  const [artifactCount, setArtifactCount] = useState(0);
  const [sessions, setSessions] = useState<SessionRow[]>([]);
  const [devices, setDevices] = useState<DeviceRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ---- account details: display name ----
  const [displayNameDraft, setDisplayNameDraft] = useState('');
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileMessage, setProfileMessage] = useState<string | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);

  // ---- account details: password ----
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  const beeLevel = user?.beeLevel ?? 0;
  /**
   * What the store enforces: max(base, active purchased membership). The
   * membership-status payload is the live source (freshly computed per request);
   * /me's effectiveBeeLevel is the fallback for the moment it is still loading.
   */
  const effectiveLevel =
    membership?.effectiveBeeLevel ?? user?.effectiveBeeLevel ?? beeLevel;
  const nextLevel = effectiveLevel < 4 ? effectiveLevel + 1 : null;
  const hasMembership = membership?.membershipLevel != null;
  /** A renewal is possible while a membership is live; a purchase needs headroom. */
  const canUpgrade = effectiveLevel < 4 || hasMembership;

  /** The tier's own color drives the hero's hex wash and the level numeral. */
  const tierHex = (() => {
    if (effectiveLevel >= 4) return '#a73afd'; // queen — the gradient is spoken by the avatar
    return beeMark(effectiveLevel).hex || '#6c707e'; // larva has no fill; fall back to muted ink
  })();

  const tierTextClass = (() => {
    switch (beeMark(effectiveLevel).tier) {
      case 'worker':
        return 'text-accent';
      case 'forager':
        return 'text-success';
      case 'guard':
        return 'text-warning';
      case 'queen':
        return 'bg-gradient-to-r from-jb-orange via-accent2 to-jb-purple bg-clip-text text-transparent';
      default:
        return 'text-ink';
    }
  })();

  const userInitial = (user?.displayName ?? user?.email ?? '?')
    .charAt(0)
    .toUpperCase();

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [me, lib, activeSessions, activeDevices, membershipStatus] =
        await Promise.all([
          api.get<PublicUser>('/api/v1/me'),
          api.get<Library>('/api/v1/me/library'),
          api.get<SessionRow[]>('/api/v1/me/sessions'),
          api.get<DeviceRow[]>('/api/v1/me/devices'),
          api.getMembershipStatus(),
        ]);
      setUser(me);
      setDisplayNameDraft(me.displayName);
      setArtifactCount(lib.entitlements?.length ?? 0);
      setSessions(activeSessions);
      setDevices(activeDevices);
      setMembership(membershipStatus);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('common.error'));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function saveProfile(event: React.FormEvent) {
    event.preventDefault();
    const name = displayNameDraft.trim();
    if (!name || name === user?.displayName) return;
    setSavingProfile(true);
    setProfileMessage(null);
    setProfileError(null);
    try {
      await api.put('/api/v1/me', { displayName: name });
      if (user) setUser({ ...user, displayName: name });
      patchUser({ displayName: name });
      setProfileMessage(t('account.profileSaved'));
    } catch (e) {
      setProfileError(e instanceof Error ? e.message : t('common.error'));
    } finally {
      setSavingProfile(false);
    }
  }

  async function revokeSession(sessionId: string) {
    await api.delete(`/api/v1/me/sessions/${sessionId}`);
    setSessions((current) => current.filter((s) => s.sessionId !== sessionId));
  }

  async function revokeDevice(deviceId: string) {
    await api.delete(`/api/v1/me/devices/${deviceId}`);
    setDevices((current) =>
      current.map((d) =>
        d.deviceId === deviceId ? { ...d, revoked: true } : d,
      ),
    );
  }

  async function changePassword(event: React.FormEvent) {
    event.preventDefault();
    setPasswordMessage(null);
    setPasswordError(null);
    try {
      await api.put('/api/v1/me/password', {
        currentPassword,
        newPassword,
      });
      setPasswordMessage(t('account.passwordChanged'));
      setCurrentPassword('');
      setNewPassword('');
    } catch (e) {
      setPasswordError(
        e && typeof e === 'object' && 'detail' in e
          ? String((e as { detail?: string }).detail)
          : t('common.error'),
      );
    }
  }

  return (
    <div className="relative">
      <HexWash fade="to bottom" />
      <div className="page-shell space-y-6">
        <PageHeader title={t('account.title')} />
        {error ? (
          <ErrorState message={error} onRetry={() => void load()} />
        ) : loading ? (
          <LoadingGrid />
        ) : user ? (
          <>
            {/* Hero — the hive passport. One line says where you stand and what to
        do next: tier crest, level, membership deadline, renew/purchase. */}
            <BlurFade>
              <div className="hive-hero rounded-lg p-5 sm:p-6 card">
                <BorderBeam size={100} duration={7} />
                <BorderBeam size={100} duration={7} reverse delay={3.5} />
                <div
                  className="absolute -right-6 -top-10 hidden h-60 w-60 sm:block"
                  aria-hidden="true"
                >
                  <HexCluster color={tierHex} />
                </div>

                <div className="relative flex flex-wrap items-center gap-5">
                  {/* Identity cell: the avatar is a honeycomb hexagon, brand-filled. */}
                  <div className="hive-avatar shrink-0" aria-hidden="true">
                    <span className="hive-avatar__initial">{userInitial}</span>
                  </div>

                  <div className="min-w-0 flex-1">
                    <h2 className="flex flex-wrap items-center gap-2 text-xl font-bold">
                      {user.displayName}
                      {user.roles.map((role) => (
                        <Badge
                          key={role}
                          variant="outline"
                          className={cn(badgeBaseClass, badgeToneClass.muted)}
                        >
                          {t(`role.${role}`)}
                        </Badge>
                      ))}
                    </h2>
                    <p className="mt-0.5 text-sm text-muted">{user.email}</p>

                    {/* The one level line: crest, tier name, level, membership
           deadline (or the next rung), then its action. */}
                    <div
                      className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2"
                      data-testid="account-level-line"
                    >
                      <span className={cn('shrink-0', tierTextClass)}>
                        <BeeCrest level={effectiveLevel} size={30} />
                      </span>
                      <p className="flex items-baseline gap-1.5 text-[1.35rem] font-bold leading-none tracking-tight">
                        <span>{t(`beeLevel.${effectiveLevel}`)}</span>
                        <span className={cn('tabular-nums', tierTextClass)}>
                          Lv{effectiveLevel}
                        </span>
                      </p>
                      {hasMembership ? (
                        <span
                          className="inline-flex items-center gap-2 text-sm text-muted"
                          data-testid="account-membership-active"
                        >
                          <span
                            className="hive-dot"
                            style={{ background: tierHex }}
                            aria-hidden="true"
                          />
                          {t('account.memberUntil', {
                            date: formatDate(
                              membership?.membershipExpiresAt ?? '',
                            ),
                          })}
                        </span>
                      ) : nextLevel !== null ? (
                        <span className="text-sm text-muted">
                          {t('account.levelNext', {
                            next: t(`beeLevel.${nextLevel}`),
                          })}
                        </span>
                      ) : (
                        <span className="text-sm text-muted">
                          {t('account.levelTop')}
                        </span>
                      )}
                      {canUpgrade && (
                        <Link
                          to="/store/membership"
                          className="btn btn-primary shrink-0 hive-cta"
                          data-testid="account-membership-cta"
                        >
                          {hasMembership
                            ? t('membership.renew')
                            : t('account.membershipCta')}
                        </Link>
                      )}
                    </div>

                    {/* Holdings at a glance: artifacts owned, signed-in devices. */}
                    <div className="mt-2.5 flex items-center gap-4 text-sm text-muted">
                      <span data-testid="account-artifact-count">
                        {t('account.artifactsCount')}
                        {` `}
                        <span className="font-semibold tabular-nums text-ink">
                          {artifactCount}
                        </span>
                      </span>
                      <span data-testid="account-device-count">
                        {t('account.devices')}
                        {` `}
                        <span className="font-semibold tabular-nums text-ink">
                          {devices.length}
                        </span>
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </BlurFade>

            {/* Account management: credentials and sign-in footprint. */}
            <div className="grid gap-4 lg:grid-cols-2 lg:gap-5">
              {/* Account details: display name and password live together. */}
              <BlurFade delay={0.08}>
                <div className="rounded-lg p-5 card">
                  <h2 className="mb-3 font-semibold">
                    {t('account.editProfile')}
                  </h2>
                  <form
                    className="flex flex-col gap-2 sm:flex-row sm:items-end"
                    onSubmit={saveProfile}
                  >
                    <label className="w-full text-sm">
                      {t('account.displayName')}
                      <input
                        value={displayNameDraft}
                        onChange={(e) => setDisplayNameDraft(e.target.value)}
                        required
                        minLength={1}
                        maxLength={64}
                        className="input mt-1"
                      />
                    </label>
                    <button
                      disabled={
                        savingProfile ||
                        displayNameDraft.trim() === user.displayName
                      }
                      className="btn btn-primary shrink-0 self-end whitespace-nowrap"
                    >
                      {t('common.confirm')}
                    </button>
                  </form>
                  {profileMessage && (
                    <p className="alert alert-success mt-2" role="status">
                      {profileMessage}
                    </p>
                  )}
                  {profileError && (
                    <p className="alert alert-error mt-2" role="alert">
                      {profileError}
                    </p>
                  )}

                  <div
                    className="my-4 border-t border-line"
                    aria-hidden="true"
                  />
                  <h3 className="mb-2 text-sm font-semibold">
                    {t('account.changePassword')}
                  </h3>
                  <form
                    className="flex flex-col gap-2 sm:flex-row sm:items-end"
                    onSubmit={changePassword}
                  >
                    <label className="w-full text-sm">
                      {t('account.currentPassword')}
                      <input
                        value={currentPassword}
                        onChange={(e) => setCurrentPassword(e.target.value)}
                        type="password"
                        required
                        autoComplete="current-password"
                        className="input mt-1"
                      />
                    </label>
                    <label className="w-full text-sm">
                      {t('account.newPassword')}
                      <input
                        value={newPassword}
                        onChange={(e) => setNewPassword(e.target.value)}
                        type="password"
                        required
                        minLength={8}
                        autoComplete="new-password"
                        className="input mt-1"
                      />
                    </label>
                    <button className="btn btn-primary shrink-0 self-end whitespace-nowrap">
                      {t('account.changePassword')}
                    </button>
                  </form>
                  {passwordMessage && (
                    <p className="alert alert-success mt-2" role="status">
                      {passwordMessage}
                    </p>
                  )}
                  {passwordError && (
                    <p className="alert alert-error mt-2" role="alert">
                      {passwordError}
                    </p>
                  )}
                </div>
              </BlurFade>

              {/* Sign-in sessions and devices */}
              <BlurFade delay={0.16}>
                <div className="rounded-lg p-5 card">
                  <h2 className="mb-3 font-semibold">
                    {t('account.signinDevices')}
                  </h2>

                  <details>
                    <summary className="cursor-pointer text-sm font-medium text-muted hover:text-accent">
                      {t('account.sessions')} ({sessions.length})
                    </summary>
                    {!sessions.length ? (
                      <EmptyState title={t('account.noSessions')} />
                    ) : (
                      <ul className="mt-2 space-y-2 text-sm">
                        {sessions.map((session) => (
                          <li
                            key={session.sessionId}
                            className="card flex flex-wrap items-center justify-between gap-2 px-3 py-2"
                          >
                            <div className="flex flex-wrap items-center gap-2">
                              <Badge
                                variant="outline"
                                className={cn(
                                  badgeBaseClass,
                                  badgeToneClass.muted,
                                )}
                              >
                                {session.clientId}
                              </Badge>
                              <Badge
                                variant="outline"
                                className={cn(
                                  badgeBaseClass,
                                  badgeToneClass.muted,
                                )}
                              >
                                {session.kind}
                              </Badge>
                              <span className="text-xs text-muted">
                                {formatDateTime(session.createdAt)}
                              </span>
                            </div>
                            <button
                              className="btn btn-danger-outline btn-sm"
                              onClick={() =>
                                void revokeSession(session.sessionId)
                              }
                            >
                              {t('account.revoke')}
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </details>

                  <details className="mt-2">
                    <summary className="cursor-pointer text-sm font-medium text-muted hover:text-accent">
                      {t('account.devices')} ({devices.length})
                    </summary>
                    {!devices.length ? (
                      <EmptyState title={t('account.noDevices')} />
                    ) : (
                      <ul className="mt-2 space-y-2 text-sm">
                        {devices.map((device) => (
                          <li
                            key={device.deviceId}
                            className="card flex flex-wrap items-center justify-between gap-2 px-3 py-2"
                          >
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-medium">{device.name}</span>
                              <Badge
                                variant="outline"
                                className={cn(
                                  badgeBaseClass,
                                  badgeToneClass.muted,
                                )}
                              >
                                {device.platform}
                              </Badge>
                              {device.revoked && (
                                <Badge
                                  variant="outline"
                                  className={cn(
                                    badgeBaseClass,
                                    badgeToneClass.danger,
                                  )}
                                >
                                  {t('account.revoked')}
                                </Badge>
                              )}
                            </div>
                            {!device.revoked && (
                              <button
                                className="btn btn-danger-outline btn-sm"
                                onClick={() =>
                                  void revokeDevice(device.deviceId)
                                }
                              >
                                {t('account.revoke')}
                              </button>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                  </details>
                </div>
              </BlurFade>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}
