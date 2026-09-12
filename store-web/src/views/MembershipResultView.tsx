import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import {
  api,
  formatFen,
  type MembershipOrder,
  type MembershipStatus,
} from '../api/client';
import { useAuth } from '../stores/auth';
import { Badge } from '@/components/ui/badge';
import BeeLevelBadge from '../components/BeeLevelBadge';
import PageHeader from '../components/PageHeader';
import {
  badgeToneClass,
  badgeBaseClass,
  badgeToneStyle,
} from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/**
 * Where payment returns land. Gateways that carry the order number back
 * (易支付/虎皮椒 return_url) poll the ORDER; Buy Me a Coffee keeps the
 * supporter on its own page and matches the payment by email, so a bare
 * visit (no orderNo) polls the caller's STATUS instead — either way the
 * page settles once the membership is live (and refreshes /me so the
 * header badge catches up).
 */
const POLL_INTERVAL_MS = 2000;
const MAX_POLLS = 60;

type ResultState = 'polling' | 'paid' | 'closed' | 'timeout' | 'missing' | 'error';

export default function MembershipResultView() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const auth = useAuth();

  const orderNo = String(searchParams.get('orderNo') ?? '');
  const [order, setOrder] = useState<MembershipOrder | null>(null);
  const [membership, setMembership] = useState<MembershipStatus | null>(null);
  const [state, setState] = useState<ResultState>('polling');

  const polls = useRef(0);
  const timer = useRef<number | undefined>(undefined);
  // Poll closures read the latest orderNo without re-arming the interval.
  const orderNoRef = useRef(orderNo);
  orderNoRef.current = orderNo;

  useEffect(() => {
    function stop() {
      if (timer.current !== undefined) {
        window.clearInterval(timer.current);
        timer.current = undefined;
      }
    }

    async function poll() {
      polls.current += 1;
      try {
        if (orderNoRef.current) {
          const current = await api.getMembershipOrder(orderNoRef.current);
          setOrder(current);
          if (current.status === 'PAID') {
            setState('paid');
            await auth.load();
            stop();
            return;
          }
          if (current.status === 'CLOSED') {
            setState('closed');
            stop();
            return;
          }
        } else {
          // Bare visit (Buy Me a Coffee): watch the membership itself.
          const current = await api.getMembershipStatus();
          setMembership(current);
          if (current.membershipLevel != null) {
            setState('paid');
            await auth.load();
            stop();
            return;
          }
        }
      } catch {
        setState('error');
        stop();
        return;
      }
      if (polls.current >= MAX_POLLS) {
        // Bounded polling gave up: unconfirmed is NOT unpaid and NOT paid —
        // stop the spinner and let the user re-check by hand (plan §6.8).
        stop();
        setState('timeout');
      }
    }

    void poll();
    timer.current = window.setInterval(() => void poll(), POLL_INTERVAL_MS);
    return stop;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderNo]);

  const succeeded: (MembershipOrder & { targetLevel?: number }) | null =
    (() => {
      if (state === 'paid' && order) return order;
      if (state === 'paid' && membership) {
        return {
          targetLevel: membership.membershipLevel ?? 0,
        } as MembershipOrder;
      }
      return null;
    })();

  return (
    <div className="mx-auto max-w-xl space-y-8 py-10">
      <PageHeader title={t('membership.result.title')} />

      {/* Polling: the callback usually lands within a couple of seconds. */}
      {state === 'polling' ? (
        <div
          className="card flex flex-col items-center gap-3 p-8 text-center"
          data-testid="membership-result-polling"
        >
          <span className="size-8 animate-spin rounded-full border-2 border-line border-t-accent" />
          <p className="font-medium">{t('membership.result.waiting')}</p>
          {order ? (
            <p className="text-sm text-muted">
              {t('membership.result.order')}
              {` `}
              <code className="text-xs">{order.orderNo}</code>
              {` · ${formatFen(order.priceFen ?? 0)}`}
            </p>
          ) : (
            <p className="text-sm text-muted">
              {t('membership.result.waitingNoOrder')}
            </p>
          )}
        </div>
      ) : state === 'paid' && succeeded ? (
        <div
          className="card flex flex-col items-center gap-4 p-8 text-center"
          data-testid="membership-result-paid"
        >
          <span className="text-4xl" aria-hidden="true">
            🐝
          </span>
          <p className="text-lg font-semibold">
            {t('membership.result.success')}
          </p>
          <BeeLevelBadge level={succeeded.targetLevel ?? 0} />
          {succeeded.durationDays ? (
            <p className="text-sm text-muted">
              {t('membership.result.paidFor', {
                n: succeeded.durationDays ?? 0,
              })}
            </p>
          ) : null}
          <Link to="/store/library" className="btn btn-primary">
            {t('membership.result.browse')}
          </Link>
        </div>
      ) : state === 'timeout' ? (
        <div
          className="card flex flex-col items-center gap-3 p-8 text-center"
          data-testid="membership-result-timeout"
        >
          <p className="font-medium">{t('membership.result.timeout')}</p>
          <p className="text-sm text-muted">{t('membership.result.timeoutHint')}</p>
          <div className="flex flex-wrap justify-center gap-2">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => {
                polls.current = 0;
                setState('polling');
                // Re-arming happens through the orderNo effect: nudge it by
                // remounting via key is overkill; call the effect's poll loop
                // by reloading the view state.
                window.location.reload();
              }}
            >
              {t('membership.result.recheck')}
            </button>
            <Link to="/store/membership" className="btn btn-ghost">
              {t('nav.membership', { defaultValue: t('membership.title') })}
            </Link>
          </div>
        </div>
      ) : state === 'closed' ? (
        <div
          className="card flex flex-col items-center gap-3 p-8 text-center"
          data-testid="membership-result-closed"
        >
          <Badge
            variant="outline"
            className={cn(badgeBaseClass, badgeToneClass.gold)}
            style={badgeToneStyle.gold}
          >
            {t('membership.result.closed')}
          </Badge>
          <p className="text-sm text-muted">
            {t('membership.result.closedHint')}
          </p>
          <Link to="/store/membership" className="btn btn-primary">
            {t('membership.result.reorder')}
          </Link>
        </div>
      ) : (
        <div className="card flex flex-col items-center gap-3 p-8 text-center">
          <Badge
            variant="outline"
            className={cn(badgeBaseClass, badgeToneClass.danger)}
          >
            {t('membership.result.error')}
          </Badge>
          <p className="text-sm text-muted">
            {t('membership.result.errorHint')}
          </p>
          <Link to="/store/membership" className="btn btn-primary">
            {t('membership.result.reorder')}
          </Link>
        </div>
      )}
    </div>
  );
}
