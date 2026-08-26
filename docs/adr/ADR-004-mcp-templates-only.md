# ADR-004: MCP listings publish templates, never secrets

Status: Accepted (design §6.4, §18.4)

MCP artifacts are reviewed configuration templates: transport, HTTPS `urlTemplate` (or a
non-composed STDIO `commandTemplate`), `requiredSecrets` marked sensitive, `defaultEnabled:
false`, tool policy disabled by default. Scanner rules reject HTTP URLs, enabled-by-default
templates, shell composition and non-sensitive secret declarations. Installed servers stay
disabled until the user supplies secrets locally.

Verified by `PackageScannerTest.enforcesMcpTemplatePolicy`.
