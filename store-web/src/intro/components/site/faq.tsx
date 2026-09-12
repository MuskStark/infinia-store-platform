
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";

export function Faq() {
  const { t } = useLocale();

  return (
    <section id="faq" className="relative w-full py-24">
      <div className="mx-auto max-w-4xl px-6">
        <SectionHeading
          eyebrow={t.faq.eyebrow}
          title={t.faq.heading}
          sub={t.faq.sub}
          className="mb-12"
        />
        <div className="flex flex-col gap-3">
          {t.faq.items.map((item) => (
            <details
              key={item.q}
              className="group rounded-2xl border border-white/10 bg-white/[0.03] px-6 py-4 transition-colors open:border-white/20 open:bg-white/[0.06]"
            >
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 text-left text-base font-medium text-white [&::-webkit-details-marker]:hidden">
                {item.q}
                <span
                  aria-hidden="true"
                  className="shrink-0 font-mono text-lg text-[var(--color-jb-purple)] transition-transform duration-300 group-open:rotate-45"
                >
                  +
                </span>
              </summary>
              <p className="mt-3 text-sm leading-relaxed text-neutral-400">{item.a}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
