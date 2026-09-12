
import { BentoGrid, BentoGridItem } from "@/intro/components/ui/bento-grid";
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";

/* Small decorative headers for each bento cell — pure markup, no assets. */

function AgentArt() {
  return (
    <div className="flex h-full min-h-32 w-full flex-col justify-end gap-2 rounded-xl bg-gradient-to-br from-neutral-900 to-black p-4">
      <div className="max-w-[80%] self-start rounded-2xl rounded-bl-sm bg-white/10 px-3 py-1.5 text-xs text-neutral-300">
        Split this workbook by region and email each office
      </div>
      <div className="max-w-[85%] self-end rounded-2xl rounded-br-sm bg-gradient-to-r from-[var(--color-jb-purple)]/30 to-[var(--color-accent)]/30 px-3 py-1.5 text-xs text-neutral-200">
        Plan: 1. excel.split → 2. email.send (approval needed) → 3. done
      </div>
    </div>
  );
}

function FlowArt() {
  return (
    <div className="relative flex h-full min-h-32 w-full items-center justify-center rounded-xl bg-gradient-to-br from-neutral-900 to-black">
      <svg viewBox="0 0 260 100" className="h-full w-full max-w-[240px]">
        <path d="M40 50 H100 M140 30 H180 M140 70 H180" stroke="rgba(255,255,255,.25)" strokeWidth="2" />
        <circle cx="30" cy="50" r="10" fill="#0b70f5" fillOpacity=".85" />
        <circle cx="120" cy="50" r="10" fill="#a73afd" fillOpacity=".85" />
        <circle cx="200" cy="30" r="10" fill="#fc801d" fillOpacity=".85" />
        <circle cx="200" cy="70" r="10" fill="#21d789" fillOpacity=".85" />
      </svg>
    </div>
  );
}

function PluginArt() {
  return (
    <div className="flex h-full min-h-32 w-full flex-col justify-center gap-1.5 rounded-xl bg-gradient-to-br from-neutral-900 to-black p-4 font-mono text-[11px] text-neutral-400">
      <div><span className="text-[var(--color-jb-green)]">$</span> fengyu init --runtime go</div>
      <div><span className="text-[var(--color-jb-green)]">$</span> fengyu check <span className="text-neutral-600">✓ manifest ok</span></div>
      <div><span className="text-[var(--color-jb-green)]">$</span> fengyu build <span className="text-[var(--color-jb-purple)]">→ my-plugin.fyp</span></div>
    </div>
  );
}

function SkillArt() {
  return (
    <div className="relative flex h-full min-h-32 w-full items-center justify-center rounded-xl bg-gradient-to-br from-neutral-900 to-black">
      {["SKILL.md · csv-alchemy", "SKILL.md · sql-surgery", "SKILL.md · invoice-rules"].map((s, i) => (
        <div
          key={s}
          className="absolute w-40 rounded-lg border border-white/10 bg-neutral-900 px-3 py-2 font-mono text-[10px] text-neutral-400 shadow-lg"
          style={{ transform: `translate(${(i - 1) * 34}px, ${i * -8}px) rotate(${(i - 1) * 4}deg)`, zIndex: i }}
        >
          {s}
        </div>
      ))}
    </div>
  );
}

function BrowserArt() {
  return (
    <div className="flex h-full min-h-32 w-full flex-col rounded-xl bg-gradient-to-br from-neutral-900 to-black p-3">
      <div className="flex gap-1.5">
        <span className="h-2 w-2 rounded-full bg-[#fe2857]/70" />
        <span className="h-2 w-2 rounded-full bg-[#fc801d]/70" />
        <span className="h-2 w-2 rounded-full bg-[#21d789]/70" />
        <span className="ml-2 flex-1 rounded-full bg-white/10 px-2 py-0.5 font-mono text-[9px] text-neutral-500">
          localhost — isolated context
        </span>
      </div>
      <div className="mt-3 flex-1 rounded-lg border border-white/10 bg-black/60 p-2 font-mono text-[10px] text-neutral-500">
        <span className="text-[var(--color-accent)]">tool:</span> browser_click(ref=stable-12)
        <span className="ml-2 text-[var(--color-jb-green)]">✓ effect: read-only</span>
      </div>
    </div>
  );
}

function ComputerArt() {
  return (
    <div className="relative flex h-full min-h-32 w-full items-center justify-center rounded-xl bg-gradient-to-br from-neutral-900 to-black">
      <div className="w-44 rounded-lg border border-white/10 bg-neutral-900 p-2 shadow-lg">
        <div className="h-12 rounded bg-gradient-to-br from-[var(--color-accent)]/30 to-[var(--color-jb-purple)]/30" />
        <div className="mt-2 space-y-1">
          <div className="h-1.5 w-3/4 rounded bg-white/10" />
          <div className="h-1.5 w-1/2 rounded bg-white/10" />
        </div>
      </div>
      <svg viewBox="0 0 24 24" className="absolute bottom-4 right-8 h-5 w-5 drop-shadow">
        <path d="M5 3l14 8-6.5 1.5L9 19 5 3z" fill="#fff" stroke="#000" strokeWidth="1" />
      </svg>
      <span className="absolute left-4 top-3 rounded-full border border-[#fe2857]/40 bg-[#fe2857]/10 px-2 py-0.5 font-mono text-[9px] uppercase tracking-wider text-[#fe2857]">
        approval required
      </span>
    </div>
  );
}

const ART = [AgentArt, FlowArt, PluginArt, SkillArt, BrowserArt, ComputerArt];

export function Features() {
  const { t } = useLocale();

  return (
    <section id="features" className="relative w-full py-24">
      <div className="pointer-events-none absolute inset-0 bg-grid-small-white [mask-image:radial-gradient(ellipse_at_center,black,transparent_70%)] opacity-60" />
      <div className="relative mx-auto max-w-7xl px-6">
        <SectionHeading
          eyebrow={t.features.eyebrow}
          title={t.features.heading}
          sub={t.features.sub}
          className="mb-14"
        />
        <BentoGrid className="mx-auto max-w-6xl">
          {t.features.items.map((item, i) => {
            const Art = ART[i % ART.length];
            return (
              <BentoGridItem
                key={item.title}
                title={item.title}
                description={item.description}
                header={<Art />}
                icon={
                  <span className="font-mono text-[10px] uppercase tracking-widest text-neutral-600">
                    {String(i + 1).padStart(2, "0")}
                  </span>
                }
                className="min-h-64"
              />
            );
          })}
        </BentoGrid>
      </div>
    </section>
  );
}
