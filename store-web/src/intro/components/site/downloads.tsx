import SiteLink from "@/intro/components/SiteLink";

import { WobbleCard } from "@/intro/components/ui/wobble-card";
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";
import { LINKS } from "@/intro/site/config";

const OS_ICONS: Record<string, string> = {
  Windows: "M3 5.5 10.5 4.4v7.1H3V5.5zm0 13 7.5 1.1v-7H3v5.9zM11.5 4.2 21 3v8.5h-9.5V4.2zm0 15.6L21 21v-8.5h-9.5v7.3z",
  macOS:
    "M16.7 12.9c0-2.4 2-3.6 2.1-3.7-1.1-1.7-2.9-1.9-3.5-1.9-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.86-1.6.02-3.1.95-4 2.4-1.7 3-.4 7.3 1.2 9.7.8 1.2 1.8 2.5 3.1 2.4 1.2-.05 1.7-.8 3.2-.8s1.9.8 3.2.77c1.3-.02 2.2-1.2 3-2.4.9-1.4 1.3-2.7 1.3-2.8-.03-.01-2.6-1-2.7-3.9zM14.4 5.1c.7-.8 1.1-1.9 1-3.1-1 .04-2.2.67-2.9 1.5-.6.7-1.2 1.9-1 3 1.1.1 2.2-.6 2.9-1.4z",
  Linux:
    "M12 2c-2 0-3 1.6-3 3.4 0 1.3.2 2-.4 3.2-.9 1.6-2.4 3.3-2.6 5.6-.1 1.4.2 2.6-.3 3.9-.4 1-.3 2 .5 2.5.9.6 2.2.2 3.2.8.8.5 1.7.6 2.6.6s1.8-.1 2.6-.6c1-.6 2.3-.2 3.2-.8.8-.5.9-1.5.5-2.5-.5-1.3-.2-2.5-.3-3.9-.2-2.3-1.7-4-2.6-5.6-.6-1.2-.4-1.9-.4-3.2C15 3.6 14 2 12 2zm-1.5 4.2c.4 0 .8.5.8 1.1s-.4 1.1-.8 1.1-.8-.5-.8-1.1.4-1.1.8-1.1zm3 0c.4 0 .8.5.8 1.1s-.4 1.1-.8 1.1-.8-.5-.8-1.1.4-1.1.8-1.1zM12 9.3c.8 0 1.9.6 1.9 1.1 0 .4-.6 1.4-1 1.4-.3 0-.5-.3-.9-.3s-.6.3-.9.3c-.4 0-1-1-1-1.4 0-.5 1.1-1.1 1.9-1.1z",
};

export function Downloads() {
  const { t } = useLocale();

  return (
    <section id="downloads" className="relative w-full py-24">
      <div className="mx-auto max-w-7xl px-6">
        <SectionHeading
          eyebrow={t.downloads.eyebrow}
          title={t.downloads.heading}
          sub={t.downloads.sub}
          className="mb-14"
        />

        <div className="mx-auto grid max-w-6xl gap-5 md:grid-cols-3">
          {t.downloads.cards.map((card) => (
            <WobbleCard
              key={card.os}
              containerClassName="h-full bg-gradient-to-br from-neutral-900 to-black border border-white/10"
              className="h-full"
            >
              <div className="flex flex-col h-full">
                <svg viewBox="0 0 24 24" className="h-8 w-8 fill-white/80" aria-hidden="true">
                  <path d={OS_ICONS[card.os] ?? OS_ICONS.Windows} />
                </svg>
                <h3 className="mt-4 text-2xl font-bold text-white">{card.os}</h3>
                <p className="mt-2 flex-1 text-sm leading-relaxed text-neutral-400">
                  {card.detail}
                </p>
                <SiteLink
                  href={LINKS.releases}
                  target="_blank"
                  rel="noreferrer"
                  className="mt-6 inline-flex w-fit items-center gap-1.5 rounded-full bg-white px-5 py-2 text-sm font-semibold text-black transition-colors hover:bg-neutral-200"
                >
                  {card.cta}
                  <span aria-hidden="true">↓</span>
                </SiteLink>
              </div>
            </WobbleCard>
          ))}
        </div>

        <div className="mx-auto mt-5 max-w-6xl">
          <WobbleCard
            containerClassName="bg-gradient-to-r from-[#1b1035] via-[#0d0d20] to-[#0b1c33] border border-white/10"
          >
            <div className="grid gap-6 md:grid-cols-[1.6fr_1fr] md:items-center">
              <div>
                <h3 className="text-xl font-bold text-white md:text-2xl">
                  {t.downloads.webTitle}
                </h3>
                <p className="mt-2 max-w-2xl text-sm leading-relaxed text-neutral-400">
                  {t.downloads.webDetail}
                </p>
              </div>
              <div className="flex flex-wrap gap-3 md:justify-end">
                <SiteLink
                  href={LINKS.releases}
                  target="_blank"
                  rel="noreferrer"
                  className="rounded-full border border-white/20 px-5 py-2 text-sm font-semibold text-white transition-colors hover:border-white/50"
                >
                  {t.downloads.webCta}
                </SiteLink>
                <SiteLink
                  href={LINKS.releases}
                  target="_blank"
                  rel="noreferrer"
                  className="rounded-full px-5 py-2 text-sm font-mono uppercase tracking-widest text-neutral-400 underline-offset-4 transition-colors hover:text-white hover:underline"
                >
                  {t.downloads.allReleases}
                </SiteLink>
              </div>
            </div>
          </WobbleCard>
        </div>
      </div>
    </section>
  );
}
