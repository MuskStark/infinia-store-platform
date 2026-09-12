import { Progress } from '@/components/ui/progress';
import { cn } from '@/lib/utils';

/**
 * App wrapper around the original shadcn Progress: omitting `value` flips the
 * bar into an indeterminate slide (driven by the app-level
 * `.progress-indeterminate` CSS; static under prefers-reduced-motion).
 */
export default function ProgressBar({
  value,
  className,
}: {
  /** 0..100; omit for indeterminate. */
  value?: number;
  className?: string;
}) {
  return (
    <Progress
      value={value}
      className={cn(value === undefined && 'progress-indeterminate', className)}
    />
  );
}
