import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { cn } from '@/lib/utils';

export interface SelectOption {
  value: string | number;
  label: string;
}

/**
 * Styled single-select (design §12.2 tokens). Native <select> popups are drawn
 * by the OS and clash with the store's surface/border/dark palette, so menus
 * that sit inside product UI use this listbox instead: a trigger button plus a
 * popover with hover, check mark, click-outside and full keyboard support
 * (Enter/Space/Arrow keys/Escape, design §12.6).
 *
 * The popover renders position: fixed at the trigger's viewport coordinates so
 * scroll containers (e.g. the admin tables' overflow-x-auto wrappers) can never
 * clip it; it flips above the trigger when the viewport has no room below and
 * closes on scroll/resize instead of detaching from its anchor.
 */
export default function SelectMenu({
  value,
  options,
  onValueChange,
  ariaLabel,
  disabled,
  triggerLabel,
  trigger,
  empty,
  className,
}: {
  value: string | number;
  options: SelectOption[];
  onValueChange: (value: string | number) => void;
  ariaLabel?: string;
  disabled?: boolean;
  /** Summary for the closed trigger; defaults to the selected option's label.
   * Keeps option rows free of a repeated prefix like “Sort by: …”. */
  triggerLabel?: string;
  /** Caller-styled trigger content (e.g. a badge that edits in place). */
  trigger?: ReactNode;
  /** Content for an empty options list. */
  empty?: ReactNode;
  /** Extra classes for the root wrapper (width constraints, layout). */
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [listStyle, setListStyle] = useState<Record<string, string>>({});
  const root = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLUListElement | null>(null);

  const current = options.find((option) => option.value === value);

  const choose = useCallback(
    (option: SelectOption) => {
      onValueChange(option.value);
      setOpen(false);
    },
    [onValueChange],
  );

  const toggle = useCallback(() => {
    if (disabled) return;
    setOpen((wasOpen) => {
      if (wasOpen) {
        setActiveIndex(-1);
        return false;
      }
      setActiveIndex(options.findIndex((option) => option.value === value));
      return true;
    });
  }, [disabled, options, value]);

  // Measured in the same tick the popover mounts, so positioning lands before paint.
  useLayoutEffect(() => {
    if (!open) return;
    const list = listRef.current;
    const anchor = root.current?.querySelector('button');
    if (!list || !anchor) return;
    const anchorRect = anchor.getBoundingClientRect();
    const { height: listHeight, width: listWidth } = list.getBoundingClientRect();
    const spaceBelow = window.innerHeight - anchorRect.bottom;
    const spaceAbove = anchorRect.top;
    const dropUp = spaceBelow < listHeight + 8 && spaceAbove > spaceBelow;
    setListStyle({
      left: `${Math.max(8, Math.min(anchorRect.left, window.innerWidth - Math.max(listWidth, anchorRect.width) - 8))}px`,
      top: dropUp
        ? `${anchorRect.top - listHeight - 4}px`
        : `${anchorRect.bottom + 4}px`,
      minWidth: `${anchorRect.width}px`,
    });
  }, [open]);

  const onKeydown = useCallback(
    (event: React.KeyboardEvent) => {
      if (disabled) return;
      if (!open) {
        if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
          event.preventDefault();
          toggle();
        }
        return;
      }
      switch (event.key) {
        case 'Escape':
          setOpen(false);
          break;
        case 'ArrowDown':
          event.preventDefault();
          setActiveIndex((i) => Math.min(i + 1, options.length - 1));
          break;
        case 'ArrowUp':
          event.preventDefault();
          setActiveIndex((i) => Math.max(i - 1, 0));
          break;
        case 'Enter':
        case ' ': {
          event.preventDefault();
          const option = options[activeIndex];
          if (option) choose(option);
          break;
        }
      }
    },
    [disabled, open, options, activeIndex, choose, toggle],
  );

  /** Close on outside click so the popover behaves like the account menu. */
  useEffect(() => {
    function onDocumentClick(event: MouseEvent) {
      if (
        open &&
        root.current &&
        !root.current.contains(event.target as Node) &&
        !listRef.current?.contains(event.target as Node)
      ) {
        setOpen(false);
      }
    }
    /** A fixed popover can't follow its anchor through scrolls or resizes — close instead. */
    function onViewportChange(event: Event) {
      if (event.target instanceof Node && listRef.current?.contains(event.target)) return;
      if (open) setOpen(false);
    }
    document.addEventListener('click', onDocumentClick);
    window.addEventListener('scroll', onViewportChange, true);
    window.addEventListener('resize', onViewportChange);
    return () => {
      document.removeEventListener('click', onDocumentClick);
      window.removeEventListener('scroll', onViewportChange, true);
      window.removeEventListener('resize', onViewportChange);
    };
  }, [open]);

  const triggerProps = {
    type: 'button' as const,
    disabled,
    'aria-label': ariaLabel,
    'aria-haspopup': 'listbox' as const,
    'aria-expanded': open,
    onClick: toggle,
  };

  return (
    <div ref={root} className={cn('relative', className)} onKeyDown={onKeydown}>
      {/* Trigger: either a caller-styled control (trigger prop, e.g. a badge
     that edits in place) or the default compact text button. */}
      {trigger !== undefined ? (
        <button
          {...triggerProps}
          className="inline-flex min-h-0 items-center rounded-full transition hover:opacity-85 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {trigger}
        </button>
      ) : (
        <button
          {...triggerProps}
          className="inline-flex min-h-11 w-full items-center justify-between gap-1.5 rounded-xl border border-control/60 bg-surface-muted px-3.5 py-2 text-sm font-medium text-ink transition-colors hover:border-control focus-visible:border-accent disabled:opacity-50"
        >
          <span className="truncate">
            {triggerLabel ?? current?.label ?? '—'}
          </span>
          <svg
            className={cn(
              'shrink-0 text-muted transition-transform',
              open && 'rotate-180',
            )}
            width="12"
            height="12"
            viewBox="0 0 12 12"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M2.5 4.5L6 8l3.5-3.5"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      )}
      {open && createPortal(
        <ul
          ref={listRef}
          className="fixed z-50 max-h-64 w-max overflow-y-auto rounded-xl border border-line bg-surface py-1 shadow-xl shadow-slate-900/10 ring-1 ring-black/5 dark:shadow-black/40 dark:ring-white/5"
          style={listStyle}
          role="listbox"
        >
          {options.map((option, index) => (
            <li key={option.value}>
              <button
                type="button"
                role="option"
                aria-selected={option.value === value}
                className={cn(
                  'flex min-h-0 w-full items-center gap-2 whitespace-nowrap px-3 py-2 text-left text-sm',
                  option.value === value
                    ? 'font-semibold text-accent'
                    : index === activeIndex
                      ? 'bg-surface-muted text-ink'
                      : 'text-ink',
                )}
                onClick={() => choose(option)}
                onMouseEnter={() => setActiveIndex(index)}
              >
                <span className="w-3.5 shrink-0" aria-hidden="true">
                  {option.value === value ? '✓' : ''}
                </span>
                <span>{option.label}</span>
              </button>
            </li>
          ))}
          {!options.length && (
            <li>
              {empty ?? (
                <span className="block px-3 py-2 text-sm text-muted">—</span>
              )}
            </li>
          )}
        </ul>, document.body
      )}
    </div>
  );
}
