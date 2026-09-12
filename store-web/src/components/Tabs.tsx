import { useId, useRef, type ReactNode } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { cn } from '@/lib/utils';

type Item<T extends string> = { id: T; label: ReactNode };

/** Automatic activation, roving focus and one stable panel per tab. */
export default function Tabs<T extends string>({ value, items, onChange, label, children }: {
  value: T; items: Item<T>[]; onChange: (value: T) => void; label: string; children: ReactNode;
}) {
  const id = useId();
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const reduced = useReducedMotion();
  return <>
    <div role="tablist" aria-label={label} className="flex flex-wrap gap-1.5">
      {items.map((item, index) => <button
        key={item.id} ref={el => { buttons.current[index] = el; }}
        id={`${id}-tab-${item.id}`} role="tab" type="button"
        aria-selected={value === item.id} aria-controls={`${id}-panel-${item.id}`}
        tabIndex={value === item.id ? 0 : -1}
        onClick={() => onChange(item.id)}
        onKeyDown={event => {
          const next = event.key === 'ArrowRight' ? (index + 1) % items.length
            : event.key === 'ArrowLeft' ? (index + items.length - 1) % items.length
            : event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : -1;
          if (next < 0) return;
          event.preventDefault(); onChange(items[next].id); buttons.current[next]?.focus();
        }}
        className={cn('relative isolate min-h-11 rounded-full px-3.5 py-2 text-sm font-medium', value === item.id ? 'text-ink' : 'text-muted hover:bg-surface-muted')}
      >
        {value === item.id && <motion.span aria-hidden="true" layoutId={`${id}-selection`}
          transition={{ duration: reduced ? 0 : 0.2 }} className="absolute inset-0 -z-10 rounded-full bg-surface shadow-sm ring-1 ring-line" />}
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className={cn('size-1.5 rounded-full', value === item.id ? 'bg-brand' : 'bg-transparent')} />
          {item.label}
        </span>
      </button>)}
    </div>
    {items.map(item => <div key={item.id} role="tabpanel" id={`${id}-panel-${item.id}`}
      aria-labelledby={`${id}-tab-${item.id}`} hidden={value !== item.id} tabIndex={0}>
      {value === item.id ? children : null}
    </div>)}
  </>;
}
