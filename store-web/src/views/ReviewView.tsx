import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiRequestError, type Review } from '../api/client';
import MagicCard from '../components/MagicCard';
import { BlurFade } from '../components/magicui/blur-fade';
import { Badge } from '@/components/ui/badge';
import EmptyState from '../components/EmptyState';
import StateChip from '../components/StateChip';
import PageHeader from '../components/PageHeader';
import { formatDateTime } from '../utils/format';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/**
 * Review queue for REVIEWER/PLATFORM_ADMIN roles (design §7.3, §8). Approving
 * publishes the release immediately, so approval asks for a second click and
 * reject/request-changes require a note the publisher can act on.
 */
export default function ReviewView() {
  const { t } = useTranslation();
  const [reviews, setReviews] = useState<Review[]>([]);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [actionError, setActionError] = useState<string | null>(null);
  /** Review id currently waiting for the approval confirmation click. */
  const [confirmId, setConfirmId] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setActionError(null);
    try {
      setReviews(await api.get<Review[]>('/api/v1/reviews?status=IN_REVIEW'));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);

  function listingLink(coordinate?: string): string {
    const parts = (coordinate ?? '').replace('infinia://', '').split('/');
    return parts.length >= 3 ? `/store/listing/${parts[1]}/${parts[2]}` : '/store/browse';
  }

  function hasNotes(review: Review): boolean {
    return Boolean(notes[review.reviewId ?? '']?.trim());
  }

  function setNote(reviewId: string, value: string) {
    setNotes((current) => ({ ...current, [reviewId]: value }));
  }

  async function decide(
    review: Review,
    decision: 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES',
  ) {
    const reviewId = review.reviewId;
    if (!reviewId) return;
    // First click arms the button; the second click within the window confirms.
    if (decision === 'APPROVE' && confirmId !== reviewId) {
      setConfirmId(reviewId);
      window.setTimeout(() => {
        setConfirmId((current) => (current === reviewId ? null : current));
      }, 4000);
      return;
    }
    setConfirmId(null);
    setActionError(null);
    try {
      await api.post(`/api/v1/reviews/${reviewId}/decisions`, {
        decision,
        notes: notes[reviewId] ?? '',
      });
      await load();
    } catch (e) {
      setActionError(
        e instanceof ApiRequestError
          ? (e.detail ?? e.message)
          : e instanceof Error
            ? e.message
            : String(e),
      );
    }
  }

  return (
    <div className="page-shell space-y-6">
      <PageHeader
        title={t('review.title')}
        actions={
          <>
            <Badge
              variant="outline"
              className={cn(badgeBaseClass, badgeToneClass.accent)}
            >
              {t('review.queueCount', { n: reviews.length })}
            </Badge>
            <button
              className="btn btn-secondary btn-sm"
              disabled={loading}
              onClick={() => void load()}
            >
              {t('review.refresh')}
            </button>
          </>
        }
      />

      {actionError && (
        <p className="alert alert-error" role="alert">
          {actionError}
        </p>
      )}

      {!loading && !reviews.length && (
        <EmptyState title={t('review.queueEmpty')} />
      )}
      {reviews.map((review) => (
        <BlurFade key={review.reviewId}>
          <MagicCard className="rounded-lg p-6">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h2 className="font-semibold">
                  <Link
                    to={listingLink(review.listingCoordinate)}
                    className="hover:text-accent"
                  >
                    {review.listingName}
                  </Link>
                  {' · v'}
                  {review.version}
                </h2>
                <code className="text-xs text-muted">
                  {review.listingCoordinate}
                </code>
              </div>
              <div className="flex items-center gap-3">
                {review.submittedAt && (
                  <span className="text-xs text-muted">
                    {t('review.submitted')}:{' '}
                    {formatDateTime(review.submittedAt ?? '')}
                  </span>
                )}
                <StateChip status={review.status} />
              </div>
            </div>

            <div className="mt-4">
              <h3 className="mb-2 text-sm font-semibold">
                {t('review.findings')}
              </h3>
              {/* No findings is information too: the reviewer must see the scan ran
         and passed, not an empty section. */}
              {!review.findings?.length ? (
                <p className="alert alert-success text-sm">
                  {t('review.scanPassed')}
                </p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {review.findings.map((finding) => (
                    <li key={finding.rule} className="card p-2">
                      <Badge
                        variant="outline"
                        className={cn(
                          badgeBaseClass,
                          ['ERROR', 'CRITICAL'].includes(finding.severity ?? '')
                            ? badgeToneClass.danger
                            : badgeToneClass.muted,
                        )}
                      >
                        {finding.severity}
                      </Badge>
                      {` ${finding.rule} — ${finding.message}`}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="mt-4 flex flex-col gap-2 sm:flex-row">
              <textarea
                value={notes[review.reviewId ?? ''] ?? ''}
                onChange={(e) => setNote(review.reviewId ?? '', e.target.value)}
                placeholder={t('review.notesPlaceholder')}
                className="input"
                rows={2}
              />
              <div className="flex shrink-0 flex-col gap-2">
                <div className="flex gap-2">
                  <button
                    className={cn(
                      'btn btn-success',
                      confirmId === review.reviewId && 'animate-pulse',
                    )}
                    onClick={() => void decide(review, 'APPROVE')}
                  >
                    {confirmId === review.reviewId
                      ? t('review.approveConfirm')
                      : t('review.approve')}
                  </button>
                  <button
                    className="btn btn-secondary"
                    disabled={!hasNotes(review)}
                    title={
                      hasNotes(review)
                        ? undefined
                        : t('review.notesRequiredHint')
                    }
                    onClick={() => void decide(review, 'REQUEST_CHANGES')}
                  >
                    {t('review.requestChanges')}
                  </button>
                </div>
                <button
                  className="btn btn-danger-outline"
                  disabled={!hasNotes(review)}
                  title={
                    hasNotes(review) ? undefined : t('review.notesRequiredHint')
                  }
                  onClick={() => void decide(review, 'REJECT')}
                >
                  {t('review.reject')}
                </button>
              </div>
            </div>
            {!hasNotes(review) && (
              <p className="mt-2 text-xs text-muted">
                {t('review.notesRequiredHint')}
              </p>
            )}
          </MagicCard>
        </BlurFade>
      ))}
    </div>
  );
}
