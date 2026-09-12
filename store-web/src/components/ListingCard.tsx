import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Badge } from '@/components/ui/badge';
import BeeLevelBadge from './BeeLevelBadge';
import { formatNumber } from '../utils/format';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import type { CatalogItem } from '../api/client';
import { cn } from '@/lib/utils';

/**
 * Marketplace listing card: icon left, name + publisher right of it, summary,
 * then a meta footer row (downloads · version · type). Hairline border, small
 * radius, hover lifts the border to ink — no heavy shadows.
 */
export default function ListingCard({
  item,
  featured,
}: {
  item: CatalogItem;
  featured?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <Link
      to={`/store/listing/${item.namespace}/${item.slug}`}
      className="card group flex h-full flex-col gap-3 p-4 transition-colors hover:border-ink/40 dark:hover:border-slate-500"
    >
      {featured && (
        <div
          className="-mt-4 -mx-4 mb-0 h-1 rounded-t-lg"
          style={{ background: 'var(--hero-gradient)' }}
          aria-hidden="true"
        />
      )}
      <div className="flex items-start gap-3">
        {item.iconUrl ? (
          <img
            src={item.iconUrl}
            alt=""
            className="h-11 w-11 shrink-0 rounded-lg object-cover"
          />
        ) : (
          <div
            className="grid h-11 w-11 shrink-0 place-items-center rounded-lg text-lg font-bold text-white"
            style={{ background: 'var(--hero-gradient)' }}
            aria-hidden="true"
          >
            {item.name.charAt(0)}
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="font-semibold leading-snug">{item.name}</h3>
            <div className="flex shrink-0 items-center gap-1">
              {item.channel && item.channel !== 'stable' && (
                <Badge
                  variant="outline"
                  className={cn(badgeBaseClass, badgeToneClass.accent)}
                >
                  {t(`channel.${item.channel}`)}
                </Badge>
              )}
            </div>
          </div>
          <p className="truncate text-xs text-muted">{item.namespace}</p>
        </div>
      </div>

      <p className="line-clamp-2 text-sm leading-6 text-muted">
        {item.summary}
      </p>

      <div className="mt-auto space-y-1.5">
        {(item.category || (item.minBeeLevel && item.minBeeLevel > 0)) && (
          <div className="flex flex-wrap gap-1">
            {item.category && (
              <Badge
                variant="outline"
                className={cn(badgeBaseClass, badgeToneClass.muted)}
              >
                {item.category}
              </Badge>
            )}
            {item.minBeeLevel != null && item.minBeeLevel > 0 && (
              <BeeLevelBadge level={item.minBeeLevel} demands />
            )}
          </div>
        )}
        <div className="flex items-center justify-between text-xs text-muted">
          <span>
            {item.downloads != null ? formatNumber(item.downloads) : '—'}{' '}
            {t('discover.statsDownloads')}
          </span>
          <span className="flex items-center gap-2">
            {item.latestVersion && <span>v{item.latestVersion}</span>}
            <span className="font-medium text-ink/70">
              {t(`type.${item.type}`)}
            </span>
          </span>
        </div>
      </div>
    </Link>
  );
}
