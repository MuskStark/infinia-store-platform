import { useTranslation } from 'react-i18next';
import BeeCrest from './BeeCrest';
import { beeMark } from '../bee-levels';
import { cn } from '@/lib/utils';

/**
 * Infinia Level badge (等级徽章): every hive level carries its own crest — an
 * empty cell, the first comb, the nectar drop, the shield, the crown — so the
 * level is recognizable by silhouette alone, colored by its tier. `demands` mode
 * marks the minimum level a listing requires instead of the user's own;
 * `compact` drops the role name for tight spots (header chip).
 */

/** No capsule: the crest itself is the badge, tier-colored, with its label. */
const TIER_CLASS: Record<string, string> = {
  larva: 'text-muted',
  worker: 'text-accent',
  forager: 'text-success',
  guard: 'text-warning dark:text-amber-400',
  queen: 'text-jb-orange',
};

export default function BeeLevelBadge({
  level,
  demands = false,
  compact = false,
}: {
  level: number;
  demands?: boolean;
  compact?: boolean;
}) {
  const { t } = useTranslation();

  const safeLevel = Math.max(0, Math.min(4, level));
  const mark = beeMark(safeLevel);
  const levelName = t(`beeLevel.${safeLevel}`);

  return (
    <span
      className={cn(
        'bee-badge',
        `bee-badge--${mark.tier}`,
        compact && 'bee-badge--compact',
        'inline-flex items-center gap-1.5 whitespace-nowrap text-xs font-bold',
        TIER_CLASS[mark.tier],
        compact && 'gap-1',
      )}
      title={t('beeLevel.title')}
    >
      <BeeCrest level={safeLevel} size={compact ? 15 : 18} />
      {demands ? (
        <span className={mark.tier === 'queen' ? beeQueenLabel : undefined}>
          {t('beeLevel.requires')} {levelName} (Lv{level}+)
        </span>
      ) : compact ? (
        <span className={mark.tier === 'queen' ? beeQueenLabel : undefined}>
          Lv{level}
        </span>
      ) : (
        <span className={mark.tier === 'queen' ? beeQueenLabel : undefined}>
          {levelName} · Lv{level}
        </span>
      )}
    </span>
  );
}

/** The queen's label text is filled with the brand sweep. */
const beeQueenLabel =
  'bg-gradient-to-r from-jb-orange via-accent2 to-jb-purple bg-clip-text text-transparent';
