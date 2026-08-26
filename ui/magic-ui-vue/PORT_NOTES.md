# @infinia/magic-ui-vue — port notes (design §12.1, ADR-007)

## Provenance

- Upstream: [Magic UI](https://magicui.design) — `magicuidesign/magicui`, MIT license.
- Upstream stack: React, TypeScript, Tailwind CSS, Motion (framer-motion).
- This package: a controlled Vue 3.5 port of the components the store UI needs.
  No React runtime is ever shipped or imported (design decision 5 / ADR-007).

## Ported components

| Component | Upstream equivalent | Notes |
|---|---|---|
| `MagicCard` | magic-card | Gradient border + spotlight hover, CSS only |
| `BorderBeam` | border-beam | Conic-gradient beam rotating via CSS `@property`; static under reduced motion |
| `AnimatedGridPattern` | animated-grid-pattern | SVG grid, dash-offset drift; static under reduced motion |
| `Marquee` | marquee | CSS keyframe translate, duplicated track, pauses on hover |
| `ShimmerButton` | shimmer-button | Sheen sweep highlight; static under reduced motion |
| `BlurFade` | blur-fade | Opacity + blur-filter transition on mount; instant under reduced motion |
| `NumberTicker` | number-ticker | requestAnimationFrame count-up; renders final value instantly under reduced motion |
| `AnimatedList` | animated-list | Staggered fade-in of list items; instant under reduced motion |
| `Badge` | badge | Static presentational badge |
| `ProgressBar` | progress | Determinate bar + indeterminate slide |

## Differences from upstream

1. **Motion layer.** Upstream animates with Motion (React). This port implements the
   same visual behavior with CSS animations/transitions plus `requestAnimationFrame`
   where interpolation is required. This keeps the bundle dependency-free and makes
   `prefers-reduced-motion` enforcement trivial.
2. **Reduced motion is enforced, not opt-in.** All decorative animations collapse to
   their final/static state when the user prefers reduced motion (design §12.2, §12.6).
3. **Styling.** Components rely on consumer CSS custom properties
   (`--magic-foreground`, `--magic-accent`, `--magic-radius`, …) instead of Tailwind
   classes, so the port works in any styling environment; the store app maps its
   Tailwind tokens onto these variables.

## Updating from upstream

Upstream is vendored, not followed as a dependency. To refresh a component:
1. Copy the MIT notice from the upstream file into the ported SFC header comment.
2. Re-implement against this package's conventions (CSS variables, reduced-motion).
3. Add/adjust the visual test in `__tests__/components.spec.ts`.
