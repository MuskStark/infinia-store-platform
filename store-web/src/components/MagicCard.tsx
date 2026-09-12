import type { ComponentProps } from 'react';
import { MagicCard as OriginalMagicCard } from './magicui/magic-card';
import { cn } from '@/lib/utils';

export type MagicCardProps = ComponentProps<typeof OriginalMagicCard>;

/** Compatibility shell: business panels stay static; vendored effects remain untouched. */
export default function MagicCard({ children, className }: MagicCardProps) {
  return <div className={cn('card', className)}>{children}</div>;
}
