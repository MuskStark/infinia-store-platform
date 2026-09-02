# Infinia Store Platform

The cloud control plane of the [Infinia / FengYu](https://github.com/MuskStark) ecosystem: a
unified catalog, publishing pipeline, review workflow, signed delivery and account system for
**five artifact classes — APP, PLUGIN, SKILL, MCP, FLOW** — built on the local-first
principles of the FengYu host.

> The store extends *distribution, identity and the trust chain*. It never turns the local
> runtime into a cloud-dependent SaaS client: the host keeps running everything already
> installed when the store is offline (design §1).

- **Backend:** Java 21 · Spring Boot 4.1.x (modular monolith) · PostgreSQL · Flyway · OAuth 2.1
  authorization server · Ed25519 release signing
- **Frontend:** Vue 3.5 (English-first UI with 简体中文 switch) · Vite 7 · Pinia · vue-i18n ·
  Tailwind CSS 4 · a controlled, MIT-attributed Vue port of Magic UI
- **Tests:** 142 backend tests (unit + full HTTP integration) and 18 frontend tests

## Architecture

```text
store-platform/
├── store-contract/         # Dependency-free contract: coordinates, SemVer, DTOs, OpenAPI 3.1
├── store-domain/           # Pure domain model + policies (state machine, dependency solver,
│                           #   permission diff, rollout bucketing, UUIDv7)
├── store-infrastructure/   # JPA persistence, Flyway, local blob store, outbox relay, cache
├── store-scanner/          # Safe unpacking, manifest validation for all 5 classes,
│                           #   secret/malicious-content scanning, SBOM, Ed25519
├── store-application/      # The single deployable Spring Boot app (API + auth server)
├── store-web/              # Vue 3 store / publisher / review SPA
└── ui/magic-ui-vue/        # @infinia/magic-ui-vue — controlled Magic UI port (MIT)
```

Key decisions are frozen in [docs/adr](docs/adr/ADR-001-modular-monolith.md); the full design
lives in [docs/design/STORE_PLATFORM_DESIGN.md](docs/design/STORE_PLATFORM_DESIGN.md).

## Quickstart (single jar)

Build the SPA once, then package it with the backend into one executable Boot jar
(the Vite output is embedded under `classpath:/static`, so API, OAuth server and
web UI share a single origin and port):

```bash
yarn install
./build-jar.sh    # builds the SPA, embeds it, runs tests, verifies the jar
# faster iteration: ./build-jar.sh --skip-tests, or --skip-web to reuse dist/

java -jar store-application/target/store-application-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

Open http://localhost:8080. The `local` profile runs on H2 stored under
`~/.infinia-store` — no Docker needed. Seeded demo accounts (password `Password123!`):

| Account | Roles |
|---|---|
| `admin@infinia.local` | PLATFORM_ADMIN |
| `reviewer@infinia.local` | REVIEWER |
| `publisher@infinia.local` | PUBLISHER |
| `user@infinia.local` | USER |

The local profile seeds accounts only; its catalog starts empty. Integration tests explicitly
enable their own demo fixtures.

For deployments, point the platform at its public address so OAuth redirects and
issued tokens match it (`store.base-url=https://store.example.com`) and provide the
secrets listed under [Production-like stack](#production-like-stack-docker).

### Split frontend development

To work on the SPA with Vite hot reload, run the backend with the `dev` profile
(keeps the OAuth redirect on the Vite origin) next to the dev server:

```bash
./mvnw spring-boot:run -pl store-application -Dspring-boot.run.profiles=dev
yarn web    # :8089, proxies /api and /oauth2 to :8080
```

### FengYu host sources

Register native `.fyp` listings as a `FENGYU` source using
`http://localhost:8080/api/v1/compat/fengyu/catalog`. FengYu's direct Skill catalog is
`http://localhost:8080/api/v1/compat/fengyu/skills-catalog`; MCP entries are available at
`http://localhost:8080/api/v1/compat/fengyu/mcp-catalog` and through the Native install API.

Publisher-owned Skill/MCP blobs may also be exposed at
`http://localhost:8080/api/v1/compat/fengyu/claude-marketplace.json`. Aggregated upstream
entries are intentionally excluded from that disk-backed Git export: synchronization stores
metadata only, and FengYu downloads cause a request-scoped fetch, security scan and compatible
package build with `Cache-Control: no-store`.

### Main-application (host) updates

The store also distributes the FengYu host itself. APP listings carry the full release
matrix — installed (`INSTALLER`: NSIS exe, dmg, deb) and portable (`PORTABLE`: zip,
AppImage, portable web archive, fat JAR) distributions per platform/arch, plus build
variants (`lite`, `jre`, `uos`, `web`, `jar`). Kind, platform, arch and variant are
inferred from the release filename at upload time (see `PublisherService`).

Publish a host release from its GitHub release assets:

```bash
STORE_BASE=https://store.example.com \
STORE_CLI_CLIENT_SECRET=<secret> \
  scripts/publish-app-release.sh 4.0.0 ./release-assets stable
```

The script authenticates with the store CLI client (CI service account), creates the
`official/fengyu-host` APP listing on first use, uploads every asset through the presigned
pipeline and submits for review. After reviewer approval, desktop hosts check for updates
through the anonymous signed feed:

```
GET /api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64
                        &mode=installer&variant=jre&installId=<opaque-id>
```

`mode` (`installer` | `portable` | `any`) and `variant` route the request to the matching
distribution; rollout bucketing stays stable per `installId`. Every returned artifact URL
is a short-lived HMAC ticket and the response carries the artifact SHA-256, the Ed25519
platform signature and the `keyId` for client-side verification. The advertised
`minimumSupportedVersion` floor is operator-configurable
(`STORE_APP_MINIMUM_SUPPORTED_VERSION`), and every published release serves a
sha256sum-compatible manifest at `GET /api/v1/releases/{releaseId}/checksums.txt`
(design §8.3).

### Production-like stack (Docker)

```bash
docker compose up -d          # PostgreSQL 17, Redis 7, MinIO
./build-jar.sh
java -jar store-application/target/store-application-0.1.0-SNAPSHOT.jar
```

Secrets come from the environment (`STORE_TICKET_SECRET`, `STORE_ROLLOUT_SECRET`,
`STORE_CLI_CLIENT_SECRET`, key material under `store.key-dir` → KMS in production).

## The publishing pipeline

```text
create org (reserves namespace) → create listing → draft release
  → presigned upload (HMAC ticket, size-capped)
  → submit → async scan (zip-slip / zip-bomb guards, manifest validation per class,
              secret & malicious-content rules, MCP template policy, CycloneDX SBOM)
  → auto-reject on blocking findings  |  IN_REVIEW
  → reviewer decision (self-review forbidden) → APPROVE
  → platform signs the release envelope (Ed25519) → PUBLISHED
  → outbox event → webhooks (HMAC-SHA256 signed), catalog visible
```

End-to-end this flow — including malicious-package auto-rejection — is covered by
`PublishingPipelineTest`.

## API

- Contract (source for clients): [`store-contract/src/main/resources/contract/openapi.yaml`](store-contract/src/main/resources/contract/openapi.yaml)
- Base path `/api/v1`; errors are RFC 9457 `application/problem+json` with stable `code` +
  `traceId`, localized in **English and 简体中文** via `Accept-Language`
- Anonymous: catalog, listing details, resolutions, update feed, download tickets, registration
- Authenticated: library, favorites, sessions/devices, publisher, review, admin
- The SPA logs in with OAuth 2.1 Authorization Code + **PKCE**; the CLI uses client
  credentials mapped to a seeded CI service account

Regenerate frontend API types after changing the contract:

```bash
yarn workspace @infinia/store-web gen:api
```

## Security properties

- SHA-256 content addressing + Ed25519 platform signatures on every artifact; revocable keys
  with `keyId` rotation support
- MCP listings ship **reviewed templates only** — never secrets; installs disabled by default
- Sessions are ledgered in the token (`sid` claim) and revoking one invalidates the JWT at the
  resource server immediately
- Rollout bucketing uses `HMAC-SHA256(rolloutSecret, installId)` — never account, email or IP
- Scanning failures cannot be bypassed by publishers; reviewers cannot approve their own
  releases; all publisher/admin writes append audit events

## Development

```bash
./mvnw verify                        # backend: 142 tests
yarn ui:test                         # magic-ui-vue port: visual/behavior tests
yarn web:test && yarn web:build      # SPA: i18n parity, client, component tests + typecheck
```

- Database changes go through Flyway only (`store-infrastructure/src/main/resources/db/migration`)
- Integration tests run on H2 in PostgreSQL compatibility mode — no Docker required; the same
  migrations execute on PostgreSQL in production
- Backend commits require English messages; UI copy is English-first with zh-CN switch and a
  test enforcing key parity

## License

GPL-3.0 (see [LICENSE](LICENSE)). The `ui/magic-ui-vue` package vendors MIT-licensed
components from [Magic UI](https://magicui.design) with attribution preserved
(see [ui/magic-ui-vue/PORT_NOTES.md](ui/magic-ui-vue/PORT_NOTES.md)).
