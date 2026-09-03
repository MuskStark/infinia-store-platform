# Changelog

## Unreleased

### SKILLHUB_REGISTRY upstream adapter — WorkBuddy open skills via the SkillHub Open API

- New upstream adapter type `SKILLHUB_REGISTRY` aggregates Tencent's open skill platform behind
  WorkBuddy (`https://api.skillhub.cn`, ~136k skills). Discovery pages the `/api/skills`
  catalog envelope — metadata-only as before, default window `pages=3` × `pageSize=100` with
  `source` / `category` / `keyword` filters and the `pages` / `pageSize` knobs riding in the
  registered URL's query string; downloads pin the synced version through
  `/api/v1/download`, whose 302 to a signed Tencent COS address is followed manually so every
  redirect hop re-passes the SSRF guard. Register with
  `POST /api/v1/admin/upstreams {"marketplaceUrl":"https://api.skillhub.cn","adapterType":"SKILLHUB_REGISTRY",...}`;
  AUTO detection also recognizes SkillHub URLs, and catalog drift (e.g. an upstream version
  bump) keeps failing downloads with 409 `upstream_drifted` until a re-sync.
- Verified by `skillhubRegistryAggregatesWorkBuddySkillsWithRedirectDownloadAndDriftGuard`
  (stub registry with the 302 download hop) and a live smoke check against the real
  `api.skillhub.cn` list/download endpoints and a payload zip (root `SKILL.md`, as the
  adapter's shallowest-root location expects).
- The upstream is now **seeded by default**: `UpstreamCatalogBootstrap` idempotently registers
  `SkillHub (WorkBuddy)` (namespace `skillhub`, default window
  `store.upstream.defaults.skillhub-url` = `https://api.skillhub.cn/api/skills?pages=1`, the
  top 100 by downloads) on first boot and indexes it, so deployments aggregate WorkBuddy's
  open skills without a manual registration. `store.upstream.defaults.enabled=false` opts out;
  seeding runs after account seeding (`SeedData` now has an explicit listener order) and the
  existing never-successfully-synced retry rule makes later boots re-index until it succeeds.
  Covered by `UpstreamDefaultsBootstrapTest` (isolated H2 + local SkillHub stub).

### fengyu-desktop is now a public OAuth client (PKCE only)

- The desktop host client registration drops its client secret
  (`STORE_DESKTOP_CLIENT_SECRET` is gone from `store.*` properties): a secret baked into the
  distributed FengYu build is public knowledge, not a credential (RFC 8252 §8.5). Sign-in is
  the standard authorization-code + PKCE loopback flow with no shared secret; FengYu
  deployments that still pair with a confidential registration keep working via
  `FENGYU_STORE_CLIENT_SECRET` on the host side.
- Consequences of the SAS 7 public-client gates (verified by `AuthAndAccountFlowTest` and a
  live two-process run): the refresh-token grant is never issued
  (`OAuth2RefreshTokenGenerator` hard-gates public clients), so the 30-minute access token
  expires into a browser re-login / anonymous degradation, and the `/oauth2/revoke`
  endpoint rejects public clients (401 — it authenticates via `code_verifier`, which a
  revocation request cannot carry). Host sign-out stays local-first: the binding and OS
  keychain entry are removed client-side, and server-side tokens expire naturally. Long-lived
  sessions remain a store-side mechanism (per-install credentials or a BFF), not a shipped
  secret.

### Admin manual upload of host-app update packages (the store replaces the FY-Proxy distribution center)

- New PLATFORM_ADMIN surface `/api/v1/admin/app-releases`: `POST` starts a manual upload
  (ensures the conventional `store.app-coordinate` listing — reserving its namespace on first
  use — drafts the release and returns the same presigned PUT URL the publisher pipeline uses),
  `DELETE /{releaseId}` hard-removes a release of any status (the app update feed and the
  FengYu compat mirror stop serving it immediately), and `GET` lists the uploads for the
  console. `POST /{releaseId}/publish` publishes instantly — the platform admin is the review
  decision, with envelope + artifact platform signing and a distinct `release.admin-publish`
  audit event.
- Version and channel are inferred from the package filename when omitted
  (`Infinia-<semver>-win32-x64-portable.zip`; a pre-release suffix names the channel, e.g.
  `-beta.1` → beta). `PublisherService.createDraftRelease`/`createUploadSession` gained a
  `platformAdmin` bypass so admins can operate the CI-owned host listing.
- Admin console gains an **Update packages** tab: visible file picker (version/channel read
  from the filename and shown as badges before upload), upload-and-publish in one click, and
  per-release delete with confirmation. zh-CN/en copy included.
- `AppReleaseFlowTest` covers the full loop: 403 for non-admins, filename inference, instant
  publish, mirror serving with mandatory sha256 digest, and deletion.

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
