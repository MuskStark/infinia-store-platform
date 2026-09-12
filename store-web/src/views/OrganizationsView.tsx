import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  api,
  type AuditEvent,
  type Organization,
  type OrganizationMember,
  type Webhook,
} from '../api/client';
import MagicCard from '../components/MagicCard';
import { Badge } from '@/components/ui/badge';
import EmptyState from '../components/EmptyState';
import LoadingGrid from '../components/LoadingGrid';
import ErrorState from '../components/ErrorState';
import PageHeader from '../components/PageHeader';
import SelectMenu from '../components/SelectMenu';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/**
 * Organization center (design §7.1, §7.3, §12.4 账号: 组织): member RBAC,
 * webhooks and the organization's audit trail. Creating an organization
 * reserves its namespace and is offered here for convenience.
 */
export default function OrganizationsView() {
  const { t } = useTranslation();

  const [orgs, setOrgs] = useState<Organization[]>([]);
  const [selected, setSelected] = useState<Organization | null>(null);
  const [members, setMembers] = useState<OrganizationMember[]>([]);
  const [webhooks, setWebhooks] = useState<Webhook[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [newOrgSlug, setNewOrgSlug] = useState('');
  const [newOrgName, setNewOrgName] = useState('');
  const [memberEmail, setMemberEmail] = useState('');
  const [memberRole, setMemberRole] = useState('PUBLISHER');
  const [webhookUrl, setWebhookUrl] = useState('');

  // Guards against stale org-switch responses: a slow request for org A must
  // never repaint the panels after the user already switched to org B (§6.9).
  const selectSeq = useRef(0);
  const [panelsLoading, setPanelsLoading] = useState(false);
  const [panelsError, setPanelsError] = useState<string | null>(null);

  async function select(org: Organization) {
    const seq = ++selectSeq.current;
    setSelected(org);
    // Drop the previous org's data immediately — never show org A's members
    // under org B's heading while the fetch is in flight.
    setMembers([]);
    setWebhooks([]);
    setAuditEvents([]);
    setPanelsLoading(true);
    setPanelsError(null);
    try {
      const [m, w, a] = await Promise.all([
        api.get<OrganizationMember[]>(
          `/api/v1/organizations/${org.organizationId}/members`,
        ),
        api.get<Webhook[]>(
          `/api/v1/organizations/${org.organizationId}/webhooks`,
        ),
        api.get<AuditEvent[]>(
          `/api/v1/organizations/${org.organizationId}/audit-events`,
        ),
      ]);
      if (seq !== selectSeq.current) return; // a newer selection superseded us
      setMembers(m);
      setWebhooks(w);
      setAuditEvents(a);
    } catch (e) {
      if (seq === selectSeq.current) {
        setPanelsError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      if (seq === selectSeq.current) setPanelsLoading(false);
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const list = await api.get<Organization[]>('/api/v1/organizations');
      setOrgs(list);
      // Only auto-select when nothing is picked yet; re-selecting would clobber
      // the member/webhook panels mid-edit.
      setSelected((current) => {
        if (!current && list.length) {
          void select(list[0]);
        }
        return current;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'error');
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);

  async function createOrg(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const org = await api.post<Organization>('/api/v1/organizations', {
        slug: newOrgSlug,
        name: newOrgName || newOrgSlug,
      });
      setNewOrgSlug('');
      setNewOrgName('');
      setOrgs(await api.get<Organization[]>('/api/v1/organizations'));
      await select(org);
    } finally {
      setBusy(false);
    }
  }

  async function addMember(event: React.FormEvent) {
    event.preventDefault();
    if (!selected || !memberEmail) return;
    setBusy(true);
    try {
      await api.post(
        `/api/v1/organizations/${selected.organizationId}/members`,
        {
          email: memberEmail,
          role: memberRole,
        },
      );
      setMemberEmail('');
      await select(selected);
    } finally {
      setBusy(false);
    }
  }

  async function changeRole(member: OrganizationMember, role: string) {
    if (!selected || role === member.role) return;
    await api.put(
      `/api/v1/organizations/${selected.organizationId}/members/${member.userId}/role`,
      { role },
    );
    await select(selected);
  }

  async function removeMember(member: OrganizationMember) {
    if (!selected) return;
    await api.delete(
      `/api/v1/organizations/${selected.organizationId}/members/${member.userId}`,
    );
    await select(selected);
  }

  async function createWebhook(event: React.FormEvent) {
    event.preventDefault();
    if (!selected || !webhookUrl) return;
    setBusy(true);
    try {
      await api.post(
        `/api/v1/organizations/${selected.organizationId}/webhooks`,
        {
          url: webhookUrl,
          events: [
            'release.published',
            'release.yanked',
            'release.quarantined',
          ],
        },
      );
      setWebhookUrl('');
      await select(selected);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page-shell space-y-8">
      <PageHeader title={t('org.title')} />
      {error ? (
        <ErrorState message={error} onRetry={() => void load()} />
      ) : loading ? (
        <LoadingGrid />
      ) : (
        <>
          <MagicCard className="rounded-lg p-6">
            <h2 className="mb-3 font-semibold">{t('org.createTitle')}</h2>
            <form
              className="flex flex-col gap-2 sm:flex-row"
              onSubmit={createOrg}
            >
              <input
                value={newOrgSlug}
                onChange={(e) => setNewOrgSlug(e.target.value)}
                required
                pattern="[a-z0-9][a-z0-9\-]{0,62}"
                placeholder={t('publisher.orgSlug')}
                className="input"
              />
              <input
                value={newOrgName}
                onChange={(e) => setNewOrgName(e.target.value)}
                placeholder={t('publisher.orgName')}
                className="input"
              />
              <button
                type="submit"
                disabled={busy}
                className="btn btn-primary shrink-0 whitespace-nowrap"
              >
                {t('common.confirm')}
              </button>
            </form>
            <p className="mt-2 text-xs text-muted">{t('org.createHint')}</p>
          </MagicCard>

          {!orgs.length && <EmptyState title={t('org.none')} />}

          {orgs.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {orgs.map((org) => (
                <button
                  key={org.organizationId}
                  className={cn(
                    'rounded-xl border px-4 py-2 text-sm font-medium',
                    selected?.organizationId === org.organizationId
                      ? 'border-accent bg-accent/10 font-semibold text-accent'
                      : 'border-line bg-surface text-muted hover:border-muted/40 hover:text-ink',
                  )}
                  onClick={() => void select(org)}
                >
                  {org.name}
                </button>
              ))}
            </div>
          )}

          {selected && (
            <>
              {panelsLoading ? (
                <p className="text-sm text-muted" role="status">
                  {t('common.loading')}
                </p>
              ) : panelsError ? (
                <ErrorState
                  message={panelsError}
                  onRetry={() => void select(selected)}
                />
              ) : (
                ''
              )}
              <section aria-labelledby="members-heading">
                <h2 id="members-heading" className="mb-3 text-lg font-semibold">
                  {t('org.members')}
                </h2>
                <ul className="space-y-2 text-sm">
                  {members.map((member) => (
                    <li
                      key={member.userId}
                      className="card flex flex-wrap items-center justify-between gap-2 p-3"
                    >
                      <div>
                        <div className="font-medium">
                          {member.displayName ?? member.email}
                        </div>
                        <div className="text-xs text-muted">{member.email}</div>
                      </div>
                      <div className="flex items-center gap-2">
                        {member.owner && (
                          <Badge
                            variant="outline"
                            className={cn(
                              badgeBaseClass,
                              badgeToneClass.accent,
                            )}
                          >
                            {t('org.owner')}
                          </Badge>
                        )}
                        <SelectMenu
                          value={member.role ?? 'PUBLISHER'}
                          className="w-36"
                          disabled={member.owner}
                          options={['PUBLISHER', 'ORG_ADMIN'].map((role) => ({
                            value: role,
                            label: t(`role.${role}`),
                          }))}
                          ariaLabel={t('org.role')}
                          onValueChange={(v) =>
                            void changeRole(member, String(v))
                          }
                        />
                        {!member.owner && (
                          <button
                            className="btn btn-danger-outline btn-sm"
                            onClick={() => void removeMember(member)}
                          >
                            {t('org.removeMember')}
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
                <form
                  className="mt-3 flex flex-col gap-2 sm:flex-row"
                  onSubmit={addMember}
                >
                  <input
                    value={memberEmail}
                    onChange={(e) => setMemberEmail(e.target.value)}
                    type="email"
                    required
                    placeholder={t('org.memberEmail')}
                    className="input"
                  />
                  <SelectMenu
                    value={memberRole}
                    className="w-40"
                    options={['PUBLISHER', 'ORG_ADMIN'].map((role) => ({
                      value: role,
                      label: t(`role.${role}`),
                    }))}
                    ariaLabel={t('org.role')}
                    onValueChange={(v) => setMemberRole(String(v))}
                  />
                  <button
                    type="submit"
                    disabled={busy}
                    className="btn btn-primary shrink-0 whitespace-nowrap"
                  >
                    {t('org.addMember')}
                  </button>
                </form>
              </section>

              <section aria-labelledby="webhooks-heading">
                <h2
                  id="webhooks-heading"
                  className="mb-3 text-lg font-semibold"
                >
                  {t('org.webhooks')}
                </h2>
                {!webhooks.length ? (
                  <EmptyState title={t('org.noWebhooks')} />
                ) : (
                  <ul className="space-y-2 text-sm">
                    {webhooks.map((webhook) => (
                      <li
                        key={webhook.webhookId}
                        className="card flex flex-wrap items-center justify-between gap-2 p-3"
                      >
                        <code className="text-xs">{webhook.url}</code>
                        <div className="flex flex-wrap gap-1">
                          {(webhook.events ?? []).map((event) => (
                            <Badge
                              key={event}
                              variant="outline"
                              className={cn(
                                badgeBaseClass,
                                badgeToneClass.muted,
                              )}
                            >
                              {event}
                            </Badge>
                          ))}
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
                <form
                  className="mt-3 flex flex-col gap-2 sm:flex-row"
                  onSubmit={createWebhook}
                >
                  <input
                    value={webhookUrl}
                    onChange={(e) => setWebhookUrl(e.target.value)}
                    type="url"
                    required
                    placeholder="https://ci.example.com/hooks/infinia"
                    className="input"
                  />
                  <button
                    type="submit"
                    disabled={busy}
                    className="btn btn-primary shrink-0 whitespace-nowrap"
                  >
                    {t('org.addWebhook')}
                  </button>
                </form>
                <p className="mt-2 text-xs text-muted">
                  {t('org.webhookHint')}
                </p>
              </section>

              <section aria-labelledby="org-audit-heading">
                <h2
                  id="org-audit-heading"
                  className="mb-3 text-lg font-semibold"
                >
                  {t('org.audit')}
                </h2>
                {!auditEvents.length ? (
                  <EmptyState title={t('common.empty')} />
                ) : (
                  <ul className="space-y-1 text-xs">
                    {auditEvents.map((event) => (
                      <li
                        key={event.eventId}
                        className="card px-3 py-2 font-mono"
                      >
                        {event.occurredAt} · {event.action} · {event.actorId}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </>
      )}
    </div>
  );
}
