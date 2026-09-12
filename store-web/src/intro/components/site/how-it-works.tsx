
import { StickyScroll } from "@/intro/components/ui/sticky-scroll-reveal";
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";

const STEP_ART = [
  <div key="a" className="h-40 w-56 rounded-2xl border border-white/10 bg-black/60 p-4 font-mono text-xs text-neutral-300">
    <div className="rounded-lg bg-white/10 px-3 py-2">“Summarize these invoices and archive the PDFs.”</div>
  </div>,
  <div key="b" className="h-40 w-56 space-y-2 rounded-2xl border border-white/10 bg-black/60 p-4 font-mono text-xs">
    {["01 · read mailbox", "02 · excel.split", "03 · email.send ⚑"].map((s) => (
      <div key={s} className="rounded-lg bg-white/5 px-3 py-1.5 text-neutral-300">{s}</div>
    ))}
  </div>,
  <div key="c" className="h-40 w-56 space-y-2 rounded-2xl border border-white/10 bg-black/60 p-4 font-mono text-[11px]">
    <div className="text-[var(--color-jb-green)]">worker (go) · jsonrpc ✓</div>
    <div className="text-neutral-400">flow runner · node 07/12</div>
    <div className="text-[var(--color-accent)]">browser tool · tab#2</div>
  </div>,
  <div key="d" className="flex h-40 w-56 flex-col items-center justify-center gap-2 rounded-2xl border border-[#fe2857]/30 bg-[#fe2857]/5 p-4">
    <span className="font-mono text-[10px] uppercase tracking-widest text-[#fe2857]">approval</span>
    <span className="text-center text-xs text-neutral-300">Send 6 emails to regional offices?</span>
    <div className="flex gap-2 pt-1">
      <span className="rounded-full bg-white px-3 py-1 text-[10px] font-semibold text-black">Approve</span>
      <span className="rounded-full border border-white/20 px-3 py-1 text-[10px] text-neutral-300">Deny</span>
    </div>
  </div>,
  <div key="e" className="h-40 w-56 space-y-2 rounded-2xl border border-white/10 bg-black/60 p-4 font-mono text-[11px]">
    <div className="text-[var(--color-jb-green)]">✓ run #481 complete</div>
    <div className="text-neutral-400">history · replayable</div>
    <div className="text-neutral-500">re-plan on failure → node 03</div>
  </div>,
];

export function HowItWorks() {
  const { t } = useLocale();

  return (
    <section id="how" className="relative w-full py-24">
      <div className="mx-auto max-w-7xl px-6">
        <SectionHeading
          eyebrow={t.how.eyebrow}
          title={t.how.heading}
          sub={t.how.sub}
          className="mb-10"
        />
      </div>
      <h3 id="how-steps-heading" className="sr-only">{t.how.heading}</h3>
      <StickyScroll
        content={t.how.steps.map((step, i) => ({
          title: step.title,
          description: step.description,
          content: STEP_ART[i],
        }))}
        contentClassName="shrink-0 items-center justify-center text-left lg:flex"
      />
    </section>
  );
}
