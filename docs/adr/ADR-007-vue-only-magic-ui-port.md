# ADR-007: Vue-only frontend; Magic UI as a controlled MIT port

Status: Accepted (design §12, §18.7)

Vue 3.5 is the only frontend runtime. Magic UI's upstream implementation is React + Tailwind +
Motion and cannot be consumed directly; after MIT license verification the needed components
were ported to `@infinia/magic-ui-vue` (see `PORT_NOTES.md`). The port is CSS-first (no Motion
dependency), styles through consumer CSS variables, and every decorative animation collapses
under `prefers-reduced-motion` (§12.2/§12.6). The existing FengYu Vuetify pages are unaffected;
this repository's SPA is Tailwind-only.

**Deviation note:** springdoc-openapi has no Spring Boot 4.1-compatible release either, so the
OpenAPI 3.1 document is hand-maintained in `store-contract` as the client-generation source
(`yarn workspace @infinia/store-web gen:api`); adding springdoc later must prove parity with
this contract, not replace it ad hoc.
