# ADR-013: React frontend consuming original Magic UI components

Status: Accepted (supersedes ADR-007)

## Context

ADR-007 pinned the store SPA to Vue 3 with a controlled in-repo Vue port
(`@infinia/magic-ui-vue`) of ten Magic UI components, on the grounds that
upstream Magic UI is React + Tailwind + Motion and "no React runtime is ever
shipped". The port drifted from upstream over time (app-specific props such as
`NumberTicker.format`, an app-specific badge tone API, CSS-variable styling
instead of Tailwind), so every upstream improvement had to be re-ported by
hand — and the store's UI was no longer actually "Magic UI", just shaped like
it.

## Decision

1. **The store-web frontend is React 19 + TypeScript + Vite 7 + Tailwind CSS 4.**
   The framework constraint from ADR-007 is lifted: Magic UI's original
   components are React, and consuming them verbatim requires React.
2. **Magic UI components are vendored verbatim from the official registry**
   (`https://magicui.design/r/<name>.json`) into `store-web/src/components/magicui/`
   and `store-web/src/components/ui/` (shadcn/ui base parts that Magic UI's
   registry builds on: badge, progress). Files stay byte-identical to upstream —
   no app-specific props, no restyling inside the component. App-specific looks
   (status tone chips, indeterminate progress) are composed at call sites via
   `className` and app-level CSS, the way the registry's own demos do it.
3. **The Vue port is deleted.** `ui/magic-ui-vue` is removed from the repo
   together with its workspace entry and test step; nothing imports it.
4. **Store design language is preserved.** The marketplace tokens (surface /
   ink / line / accent, JetBrains-brand hero gradient), the honeycomb brand
   wall (`HoneycombField`, `HexCluster`), the bee level crests (`BeeCrest`,
   `BeeLevelBadge`) and the header/footer shell carry over unchanged. The
   shadcn base tokens required by original components (`--color-background`,
   `--color-border`, …) are mapped onto the store palette in `main.css`, so
   original components render with the marketplace look out of the box.
5. **Ecosystem equivalents:** state moves from Pinia to Zustand (same store
   shapes), vue-router to React Router, vue-i18n to react-i18next (same
   nested dictionaries, `{x}` placeholder shape and pipe-plural rendering kept
   byte-compatible), and theme toggling to `next-themes` (the storage key
   `infinia.store.theme` is unchanged). Accessibility rules from §12.2/§12.6
   (visible focus, 44px touch targets, reduced-motion collapse) carry over;
   Motion-based animations inherit `prefers-reduced-motion` handling.

## Consequences

- Upstream Magic UI components can be refreshed by re-copying from the
  registry; no more per-release porting work. Vendored-file provenance and
  the registry-CSS mapping are documented in
  `store-web/src/components/magicui/README.md`.
- The SPA now ships the React runtime and the `motion` library (~150 KB gzip
  total over the Vue baseline) — accepted for fidelity to upstream.
- `PORT_NOTES.md` and the port's visual test suite are gone; store-web's RTL
  suite (15 files) is the only frontend test gate.
- Vuetify pages in the FengYu host frontend are unaffected (separate app).
