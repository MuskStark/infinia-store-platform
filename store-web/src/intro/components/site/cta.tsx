import SiteLink from "@/intro/components/SiteLink";

import { LampContainer } from "@/intro/components/ui/lamp";
import { Button as MovingBorderButton } from "@/intro/components/ui/moving-border";
import { useLocale } from "@/intro/i18n";
import { LINKS } from "@/intro/site/config";

export function CallToAction() {
  const { t } = useLocale();

  return (
    <LampContainer className="min-h-[80vh]">
      <h2 className="z-20 max-w-3xl bg-gradient-to-br from-white via-neutral-200 to-neutral-500 bg-clip-text text-center text-4xl font-bold tracking-tight text-transparent md:text-6xl">
        {t.cta.heading}
      </h2>
      <p className="z-20 mt-6 max-w-xl text-center text-neutral-400">{t.cta.sub}</p>
      <div className="z-20 mt-10 flex flex-wrap items-center justify-center gap-4">
        <MovingBorderButton
          as="a"
          href={LINKS.releases}
          target="_blank"
          rel="noreferrer"
          borderRadius="9999px"
          duration={4500}
          containerClassName="h-12 w-52 text-sm"
          borderClassName="bg-[radial-gradient(var(--color-cyan-glow)_40%,transparent_60%)] opacity-80"
          className="border-white/10 bg-black font-semibold text-white"
        >
          {t.cta.primary}
        </MovingBorderButton>
        <SiteLink
          href={LINKS.github}
          target="_blank"
          rel="noreferrer"
          className="rounded-full border border-white/15 px-7 py-3 text-sm font-semibold text-neutral-200 transition-colors hover:border-white/40 hover:text-white"
        >
          {t.cta.secondary}
        </SiteLink>
      </div>
    </LampContainer>
  );
}
