import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Badge } from '@/components/ui/badge';
import BeeLevelBadge from './BeeLevelBadge';
import { formatNumber } from '../utils/format';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import type { CatalogItem } from '../api/client';
import ArtifactTypeIcon from './ArtifactTypeIcon';
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
      className={cn("card group relative flex h-full min-w-0 flex-col gap-4 p-5 transition-colors hover:border-accent/40 hover:bg-surface-raised", featured && "border-accent/20")}
    >
      <div className="flex items-start gap-3">
        {item.iconUrl ? (
          <img
            src={item.iconUrl}
            alt=""
            className="h-11 w-11 shrink-0 rounded-lg object-cover"
          />
        ) : (
          <div className="shrink-0 text-accent">
            <ArtifactTypeIcon type={item.type} className="size-11" />
          </div>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="line-clamp-2 font-semibold leading-snug">{item.name}</h3>
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
          <p className="mt-1 truncate text-xs text-muted">{item.namespace}</p>
        </div>
      </div>

      <p className="line-clamp-2 min-h-12 text-sm leading-6 text-muted">
        {item.summary}
      </p>

      <div className="mt-auto space-y-3">
        {(item.category || (item.minBeeLevel && item.minBeeLevel > 0)) && (
          <div className="flex flex-wrap gap-1">
            {item.category && (
              <span className="text-xs text-muted">{item.category}</span>
            )}
            {item.minBeeLevel != null && item.minBeeLevel > 0 && (
              <BeeLevelBadge level={item.minBeeLevel} demands />
            )}
          </div>
        )}
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-t border-line/70 pt-3 text-xs text-muted">
          <span>
            {item.downloads != null ? formatNumber(item.downloads) : '—'}{' '}
            {t('discover.statsDownloads')}
          </span>
          <span className="flex min-w-0 flex-wrap items-center gap-2">
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
