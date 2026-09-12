
import { BackgroundBeams } from "@/intro/components/ui/background-beams";
import { EvervaultCard } from "@/intro/components/ui/evervault-card";
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";

export function Security() {
  const { t } = useLocale();

  return (
    <section id="security" className="relative w-full overflow-hidden py-24">
      <BackgroundBeams className="opacity-40" />
      <div className="relative mx-auto max-w-7xl px-6">
        <SectionHeading
          eyebrow={t.security.eyebrow}
          title={t.security.heading}
          sub={t.security.sub}
          className="mb-14"
        />

        <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[1.4fr_1fr] lg:items-center">
          <ul className="flex flex-col gap-6">
            {t.security.bullets.map((b, i) => (
              <li key={b.title} className="flex gap-4">
                <span className="mt-1 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-[var(--color-jb-green)]/40 bg-[var(--color-jb-green)]/10 font-mono text-[10px] text-[var(--color-jb-green)]">
                  {String(i + 1).padStart(2, "0")}
                </span>
                <div>
                  <h3 className="font-semibold text-white">{b.title}</h3>
                  <p className="mt-1 text-sm leading-relaxed text-neutral-400">
                    {b.description}
                  </p>
                </div>
              </li>
            ))}
          </ul>

          <div className="mx-auto w-full max-w-sm">
            <EvervaultCard text={t.security.keyValue} />
            <p className="mt-3 text-center font-mono text-[11px] uppercase tracking-widest text-neutral-500">
              {t.security.keyLabel} — {t.security.keyNote}
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
