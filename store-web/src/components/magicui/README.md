# Magic UI — original components (vendored)

These files are the **original Magic UI component sources**, copied byte-for-byte
from the official registry (`https://magicui.design/r/<name>.json`, path
`registry/magicui/*.tsx`) on 2026-09-11. The shadcn/ui base parts in
`../ui/` (`badge`, `progress`) come from the shadcn registry
(`https://ui.shadcn.com/r/styles/new-york-v4/*.json`) that the Magic UI
registry builds on.

| File | Registry name |
| --- | --- |
| `magic-card.tsx` | `magic-card` |
| `border-beam.tsx` | `border-beam` |
| `animated-grid-pattern.tsx` | `animated-grid-pattern` |
| `marquee.tsx` | `marquee` |
| `shimmer-button.tsx` | `shimmer-button` |
| `blur-fade.tsx` | `blur-fade` |
| `number-ticker.tsx` | `number-ticker` |
| `animated-list.tsx` | `animated-list` |
| `../ui/badge.tsx` | shadcn `badge` (new-york-v4) |
| `../ui/progress.tsx` | shadcn `progress` (new-york-v4) |

App-level theming for MagicCard (the official `magic-card-demo`'s
`gradientColor={theme === "dark" ? "#262626" : "#D9D9D955"}`) lives in
`../../MagicCard.tsx` — import that wrapper in views; this file stays upstream.

Upstream is MIT-licensed (`magicuidesign/magicui`, shadcn-ui/ui). Do not edit
these files: app-specific looks are composed at call sites via `className`
(see `src/utils/badgeTone.ts`) and app-level CSS in `src/styles/main.css`.
The registry CSS each component needs (marquee/shimmer keyframes) lives in
the `@theme` block of `src/styles/main.css`, taken from the same registry
JSON (`cssVars` / `css` fields). To refresh a component, re-copy its registry
content into the same path.
