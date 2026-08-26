# ADR-003: Unified release envelope and `infinia://` coordinate

Status: Accepted (design §6, §18.3)

All five artifact classes share one catalog model: `Namespace → Listing → Release → Artifact`
with dependencies, permissions, channels and rollout percentages. Every object is addressed as
`infinia://<type>/<namespace>/<slug>[@<semver>]` (`store-contract`, `InfiniaCoordinate`).
The release envelope (`schemaVersion`, artifacts with `sha256` + Ed25519 `signature` +
`keyId`, dependencies, permissions) is the canonical signed artifact; native package manifests
(plugin.json, SKILL.md, MCP template JSON, `.fyflow` manifest) remain authoritative for their
type and are validated by the scanner.
