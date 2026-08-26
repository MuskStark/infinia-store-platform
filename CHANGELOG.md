# Changelog

## 0.1.0 (2026-08-26)

Initial implementation of the Infinia Store Platform (design §1–§17, Phase 1–3 scope).

### Backend
- Modular monolith: `store-contract`, `store-domain`, `store-infrastructure`, `store-scanner`,
  `store-application` on Spring Boot 4.1.1 / Java 21 with an independent version line.
- Unified catalog for APP / PLUGIN / SKILL / MCP / FLOW with `infinia://` coordinates, SemVer
  ranges (npm-compatible semantics incl. prerelease gating), channels and rollout.
- Publishing pipeline: namespace ownership via organizations, draft releases, HMAC-ticketed
  uploads, async scanning, automatic rejection of malicious packages, human review with
  self-review prohibition, Ed25519 platform signing on approval, transactional outbox with
  HMAC-signed webhooks.
- Security: OAuth 2.1 authorization server (authorization code + PKCE, client credentials via
  a seeded CI account), JWT resource server with session-ledger revocation, problem+json
  errors localized in English and 简体中文, per-write audit events.
- Delivery: download tickets, content-addressed local blob store, app update feed with stable
  HMAC(installId) rollout bucketing and GitHub-compatible `UpdateInfo` fields.
- 142 tests: domain policies, scanner rules, and full-HTTP integration flows.

### Frontend
- `store-web` SPA (Vue 3.5.41, Vite 7, Pinia, vue-router, Tailwind CSS 4, vue-i18n en/zh-CN).
- Discover / Browse / Listing detail (install state machine + permission confirmation) /
  Library / Account (sessions & devices) / Publisher center / Review queue / OAuth callback.
- `@infinia/magic-ui-vue`: controlled MIT-licensed Vue port of ten Magic UI components,
  CSS-first, `prefers-reduced-motion` aware, with port notes and tests.
- API DTOs generated from the OpenAPI 3.1 contract (`openapi-typescript`); CI checks drift.
