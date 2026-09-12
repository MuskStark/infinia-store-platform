import type { ReactNode } from 'react';

export default function EmptyState({
  title,
  hint,
  icon,
  children,
}: {
  title: string;
  hint?: string;
  icon?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="grid place-items-center rounded-lg border border-dashed border-line px-6 py-10 text-center">
      {/* Icon well: callers may pass their own mark; the stacked squares read as
     "nothing here yet" without pulling an icon library into the bundle. */}
      <div
        className="mb-3 grid h-11 w-11 place-items-center rounded-xl bg-surface-muted text-muted"
        aria-hidden="true"
      >
        {icon ?? (
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
            <path
              d="M3.5 6.5L8 4l4 2.5L16.5 4v9.5L12 16l-4-2.5-4.5 2.5V6.5Z M8 6.5v7 M12 9.5v6.5"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </div>
      <p className="font-medium">{title}</p>
      {hint && <p className="mt-1 max-w-sm text-sm text-muted">{hint}</p>}
      {children && (
        <div className="mt-4 flex items-center gap-2">{children}</div>
      )}
    </div>
  );
}
