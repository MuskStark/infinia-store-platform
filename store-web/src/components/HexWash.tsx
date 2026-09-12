import { HexagonPattern } from './magicui/hexagon-pattern';
import { cn } from '@/lib/utils';

/**
 * The store's honeycomb signature: an original HexagonPattern wash pinned to a
 * page edge (default: the top), fading out via mask so content stays legible.
 * Wrap it in a `relative` parent and keep content above it with `relative`.
 */
export default function HexWash({
  className,
  fade = 'to bottom',
  intensity = 'page',
}: {
  className?: string;
  /** Direction the wash fades toward: `to bottom` (hero tops) or `to top`. */
  fade?: 'to bottom' | 'to top' | 'to right';
  /** `page` = full hero wash; `accent` = smaller, stronger accent wash. */
  intensity?: 'page' | 'accent';
}) {
  const mask =
    fade === 'to bottom'
      ? 'linear-gradient(to bottom, black 0%, rgba(0,0,0,0.5) 40%, transparent 100%)'
      : fade === 'to top'
        ? 'linear-gradient(to top, black 0%, rgba(0,0,0,0.5) 40%, transparent 100%)'
        : 'linear-gradient(to right, black 0%, rgba(0,0,0,0.55) 45%, transparent 100%)';
  return (
    <div
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0', className)}
      style={{
        maskImage: mask,
        WebkitMaskImage: mask,
      }}
    >
      <HexagonPattern
        radius={intensity === 'accent' ? 36 : 48}
        direction="vertical"
        className="fill-transparent stroke-[rgba(252,128,29,0.22)] dark:stroke-[rgba(252,128,29,0.4)]"
      />
    </div>
  );
}
