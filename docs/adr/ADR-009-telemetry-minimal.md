# ADR-009: Install telemetry is optional, idempotent and never the source of truth

Status: Accepted (design §13.2, §18.9)

`POST /api/v1/install-events` accepts batches keyed by an idempotency key; duplicates are
ignored, nothing about local paths, flow contents, MCP configuration or chats is uploaded, and
the local orchestrator never trusts the cloud record over local state. `installId` used for
rollout bucketing is random, opaque and resettable.
