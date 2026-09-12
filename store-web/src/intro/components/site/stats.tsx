
import { useLocale } from "@/intro/i18n";

export function StatsStrip() {
  const { t } = useLocale();

  return (
    <section className="relative z-10 w-full border-y border-white/5 bg-black/60">
      <div className="mx-auto grid w-full max-w-6xl grid-cols-2 gap-8 px-6 py-10 md:grid-cols-4">
        {t.stats.map((stat) => (
          <div key={stat.label} className="flex flex-col items-center gap-1 text-center">
            <span className="bg-gradient-to-r from-white to-neutral-400 bg-clip-text font-mono text-3xl font-bold text-transparent md:text-4xl">
              {stat.value}
            </span>
            <span className="text-sm text-neutral-500">{stat.label}</span>
          </div>
        ))}
      </div>
    </section>
  );
}
