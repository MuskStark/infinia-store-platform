import { cn } from "@/lib/utils";

/** Shared section header: mono eyebrow, large title, muted subtitle. */
export function SectionHeading({
  eyebrow,
  title,
  sub,
  align = "center",
  className,
}: {
  eyebrow: string;
  title: string;
  sub?: string;
  align?: "center" | "left";
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-4",
        align === "center" ? "items-center text-center" : "items-start text-left",
        className,
      )}
    >
      <span className="rounded-full border border-white/10 bg-white/5 px-4 py-1 font-mono text-xs uppercase tracking-widest text-[var(--color-jb-green)]">
        {eyebrow}
      </span>
      <h2 className="max-w-3xl text-balance text-3xl font-bold tracking-tight text-white md:text-5xl">
        {title}
      </h2>
      {sub ? (
        <p className="max-w-2xl text-pretty text-base leading-relaxed text-neutral-400 md:text-lg">
          {sub}
        </p>
      ) : null}
    </div>
  );
}
