
import { HoverEffect } from "@/intro/components/ui/card-hover-effect";
import { SectionHeading } from "@/intro/components/site/section-heading";
import { useLocale } from "@/intro/i18n";
import { LINKS } from "@/intro/site/config";

const SURFACE_LINKS = [LINKS.docsPlugins, LINKS.docsSkills, LINKS.docsAgent];

export function Surfaces() {
  const { t } = useLocale();

  return (
    <section id="surfaces" className="relative w-full py-24">
      <div className="mx-auto max-w-7xl px-6">
        <SectionHeading
          eyebrow={t.surfaces.eyebrow}
          title={t.surfaces.heading}
          sub={t.surfaces.sub}
          className="mb-8"
        />
        <HoverEffect
          items={t.surfaces.items.map((item, i) => ({
            title: item.title,
            description: item.description,
            link: SURFACE_LINKS[i] ?? LINKS.docs,
          }))}
          className="max-w-6xl mx-auto md:grid-cols-3"
        />
      </div>
    </section>
  );
}
