import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import {
  api,
  formatFen,
  type MembershipPlan,
  type MembershipStatus,
} from '../api/client';
import { Badge } from '@/components/ui/badge';
import { BorderBeam } from '../components/magicui/border-beam';
import BeeLevelBadge from '../components/BeeLevelBadge';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import LoadingGrid from '../components/LoadingGrid';
import PageHeader from '../components/PageHeader';
import { formatDate } from '../utils/format';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/**
 * Infinia Level purchase (会员等级购买): the caller's ladder position, the
 * purchasable tiers (one card per plan; several plans may share a level with
 * different durations) and the payable channels. Buying creates an order and
 * leaves the SPA for the gateway cashier; the return page polls the result.
 */
export default function MembershipView() {
  const { t } = useTranslation();

  const [status, setStatus] = useState<MembershipStatus | null>(null);
  const [plans, setPlans] = useState<MembershipPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [buyError, setBuyError] = useState<string | null>(null);
  const [buyingPlanId, setBuyingPlanId] = useState<string | null>(null);
  const [channel, setChannel] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [s, p] = await Promise.all([
        api.getMembershipStatus(),
        api.getMembershipPlans(),
      ]);
      setStatus(s);
      setPlans(p);
      setChannel((current) => {
        if (!current && s.channels?.length) {
          return s.channels[0];
        }
        return current;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);

  /** The ladder only climbs: a plan is buyable when it beats the effective level or renews the active tier. */
  function purchasable(plan: MembershipPlan): boolean {
    const s = status;
    if (!s) return false;
    const effective = s.effectiveBeeLevel ?? 0;
    const active = s.membershipLevel != null;
    const renewal =
      active &&
      s.membershipLevel === plan.beeLevel &&
      s.baseBeeLevel < plan.beeLevel;
    return plan.beeLevel > effective || renewal;
  }

  function planActionLabel(plan: MembershipPlan): string {
    const s = status;
    const renewal =
      s?.membershipLevel === plan.beeLevel &&
      s &&
      s.baseBeeLevel < plan.beeLevel;
    return renewal ? t('membership.renew') : t('membership.buy');
  }

  async function buy(plan: MembershipPlan) {
    setBuyError(null);
    setBuyingPlanId(plan.planId);
    try {
      const order = await api.createMembershipOrder({
        planId: plan.planId,
        channel: channel ?? undefined,
      });
      if (order.payUrl) {
        window.location.href = order.payUrl;
      } else {
        setBuyError(t('membership.errors.noPayUrl'));
      }
    } catch (e) {
      setBuyError(e instanceof Error ? e.message : String(e));
    } finally {
      setBuyingPlanId(null);
    }
  }

  const channelLabel = (c: string) =>
    t(`membership.channel.${c}`, { defaultValue: c });

  const paymentDisabled = !status?.channels?.length;

  return (
    <div className="page-shell space-y-10">
      <PageHeader title={t('membership.title')} />

      {error ? (
        <ErrorState message={error} onRetry={() => void load()} />
      ) : loading ? (
        <LoadingGrid />
      ) : status ? (
        <>
          {/* Current position: base level + the purchased membership riding on it. */}
          <section
            className="card flex flex-wrap items-center justify-between gap-4 p-4"
            data-testid="membership-status"
          >
            <div className="flex items-center gap-3">
              <BeeLevelBadge level={status.effectiveBeeLevel ?? 0} />
              <div className="text-sm">
                {status.membershipLevel != null ? (
                  <p>
                    {t('membership.activeMembership', {
                      level: status.membershipLevel,
                    })}
                    {' · '}
                    {t('membership.expires')}
                    {` ${formatDate(status.membershipExpiresAt ?? '')}`}
                  </p>
                ) : (status.effectiveBeeLevel ?? 0) > status.baseBeeLevel ? (
                  <p>{t('membership.permanentLevel')}</p>
                ) : (
                  <p className="text-muted">{t('membership.baseLevelHint')}</p>
                )}
              </div>
            </div>
            {paymentDisabled && (
              <Badge
                variant="outline"
                className={cn(badgeBaseClass, badgeToneClass.gold)}
                style={{
                  backgroundImage:
                    'linear-gradient(135deg, rgba(253,230,138,.3), rgba(245,158,11,.15))',
                }}
                data-testid="membership-unconfigured"
              >
                {t('membership.errors.notConfigured')}
              </Badge>
            )}
          </section>

          {/* Buy Me a Coffee has no order metadata: the webhook matches by the
        supporter's payment email, so tell the buyer up front. */}
          {channel === 'BMAC' && (
            <p
              className="rounded-xl border border-warning/40 bg-warning/10 p-3 text-sm"
              data-testid="membership-bmac-hint"
            >
              {t('membership.bmacHint')}
              <Link
                to="/store/membership/result"
                className="font-semibold text-accent"
              >
                {t('membership.result.title')} →
              </Link>
            </p>
          )}

          {/* Channel picker: only the configured gateway apps appear. */}
          {!paymentDisabled && (status.channels?.length ?? 0) > 1 && (
            <section className="flex items-center gap-2 text-sm">
              <span className="text-muted">{t('membership.payWith')}</span>
              <div
                className="flex gap-1 rounded-xl border border-line p-1"
                role="group"
              >
                {(status.channels ?? []).map((c) => (
                  <button
                    key={c}
                    type="button"
                    className={cn(
                      'rounded-lg px-3 py-1.5 transition-colors',
                      channel === c
                        ? 'bg-accent/10 font-semibold text-accent'
                        : 'text-muted hover:bg-surface-muted',
                    )}
                    aria-pressed={channel === c}
                    onClick={() => setChannel(c)}
                  >
                    {channelLabel(c)}
                  </button>
                ))}
              </div>
            </section>
          )}

          {buyError && (
            <p
              className="alert alert-error"
              role="alert"
              data-testid="membership-buy-error"
            >
              {buyError}
            </p>
          )}

          <section aria-labelledby="plans-heading">
            <h2 id="plans-heading" className="mb-3 text-lg font-semibold">
              {t('membership.plans')}
            </h2>
            {!plans.length ? (
              <EmptyState title={t('membership.noPlans')} />
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                {plans.map((plan) => (
                  <article
                    key={plan.planId}
                    className={cn(
                      'card relative flex flex-col gap-3 overflow-hidden p-5',
                    )}
                    data-purchasable={purchasable(plan)}
                    style={purchasable(plan) ? undefined : { opacity: 0.6 }}
                    data-testid="membership-plan"
                  >
                    {purchasable(plan) && <BorderBeam size={70} duration={6} />}
                    <div className="flex items-center justify-between">
                      <BeeLevelBadge level={plan.beeLevel} />
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.muted)}
                      >
                        {t('membership.days', { n: plan.durationDays })}
                      </Badge>
                    </div>
                    <p className="text-3xl font-bold">
                      {formatFen(plan.priceFen ?? 0)}
                    </p>
                    <p className="text-xs text-muted">
                      {t('membership.planHint', { n: plan.durationDays })}
                    </p>
                    <button
                      type="button"
                      className="btn btn-primary mt-auto w-full"
                      disabled={
                        !purchasable(plan) ||
                        paymentDisabled ||
                        buyingPlanId === plan.planId
                      }
                      title={
                        !purchasable(plan)
                          ? t('membership.errors.tooLow')
                          : undefined
                      }
                      data-testid="membership-buy"
                      onClick={() => void buy(plan)}
                    >
                      {buyingPlanId === plan.planId
                        ? t('membership.creating')
                        : purchasable(plan)
                          ? planActionLabel(plan)
                          : t('membership.owned')}
                    </button>
                  </article>
                ))}
              </div>
            )}
          </section>
        </>
      ) : null}
    </div>
  );
}
