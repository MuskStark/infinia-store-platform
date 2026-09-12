// Aceternity-inspired reveal within a keyboard-accessible, scrollbar-free region.
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/utils';

const gradients = [
  'linear-gradient(to bottom right, #06b6d4, #10b981)',
  'linear-gradient(to bottom right, #ec4899, #6366f1)',
  'linear-gradient(to bottom right, #f97316, #eab308)',
];

export const StickyScroll = ({ content, contentClassName }: {
  content: { title: string; description: string; content?: ReactNode }[];
  contentClassName?: string;
}) => {
  const [activeCard, setActiveCard] = useState(0);
  const region = useRef<HTMLDivElement | null>(null);
  const steps = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const scroller = region.current;
    if (!scroller) return;
    let frame = 0;
    const update = () => {
      const bounds = scroller.getBoundingClientRect();
      const center = bounds.top + scroller.clientHeight / 2;
      let closest = 0;
      let distance = Infinity;
      steps.current.slice(0, content.length).forEach((step, index) => {
        if (!step) return;
        const rect = step.getBoundingClientRect();
        const next = Math.abs(rect.top + rect.height / 2 - center);
        if (next < distance) { closest = index; distance = next; }
      });
      setActiveCard(closest);
    };
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(update); };
    update();
    scroller.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    return () => {
      cancelAnimationFrame(frame);
      scroller.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
    };
  }, [content.length]);

  return (
    <div ref={region} role="region" aria-labelledby="how-steps-heading" tabIndex={0}
      className="intro-step-scroll relative mx-auto h-[min(38rem,80vh)] lg:h-60 overflow-y-auto rounded-2xl outline-none focus-visible:ring-1 focus-visible:ring-white/30 grid w-full max-w-5xl gap-10 px-6 lg:grid-cols-[minmax(0,1fr)_20rem] lg:gap-20">
      <div>
        {content.map((item, index) => (
          <div key={item.title} ref={node => { steps.current[index] = node; }}
            className="flex min-h-[min(38rem,80vh)] flex-col justify-center gap-6 py-12 lg:min-h-60 lg:py-6">
            <h3 className={cn('text-2xl font-bold text-slate-100 transition-opacity motion-reduce:transition-none', activeCard !== index && 'lg:opacity-40')}>{item.title}</h3>
            <p className="max-w-sm text-slate-300">{item.description}</p>
            <div className="flex h-60 w-full max-w-80 items-center justify-center overflow-hidden rounded-xl lg:hidden"
              style={{ background: gradients[index % gradients.length] }}>
              {item.content}
            </div>
          </div>
        ))}
      </div>
      <div className={cn('sticky top-0 hidden h-60 w-80 self-start items-center justify-center overflow-hidden rounded-xl lg:flex', contentClassName)}
        style={{ background: gradients[activeCard % gradients.length] }}
        role="img" aria-label={content[activeCard]?.title}>
        {content[activeCard]?.content ?? null}
      </div>
    </div>
  );
};
