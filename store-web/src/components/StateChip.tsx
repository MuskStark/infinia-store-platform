import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/**
 * Release state chip. Never relies on color alone — the label is always visible
 * (design §12.6 accessibility rule). The status enum is localized; unknown
 * values fall back to the raw status so new backend states never render blank.
 *
 * The original shadcn Badge carries the shape; the app supplies the status
 * palette as classNames on top of it.
 */

function toneFor(status: string): string {
  switch (status) {
    case 'PUBLISHED':
    case 'APPROVED':
      return badgeToneClass.success;
    case 'REJECTED':
    case 'QUARANTINED':
    case 'YANKED':
      return badgeToneClass.danger;
    // In-flight states are business info (blue), never brand gold; while
    // CHANGES_REQUESTED asks the publisher for input (warning/amber).
    case 'IN_REVIEW':
    case 'SCANNING':
    case 'UPLOADING':
      return badgeToneClass.info;
    case 'CHANGES_REQUESTED':
      return badgeToneClass.warning;
    default:
      return badgeToneClass.muted;
  }
}

export default function StateChip({ status = '' }: { status?: string }) {
  const { t, i18n } = useTranslation();

  const label =
    status && i18n.exists(`state.${status}`) ? t(`state.${status}`) : status;

  return (
    <Badge variant="outline" className={cn(badgeBaseClass, toneFor(status))}>
      {label}
    </Badge>
  );
}
