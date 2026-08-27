-- Infinia Store Platform — upstream aggregation sources (design §2.1: the store
-- replaces the host's own Claude/Codex/Grok marketplace aggregation).
-- One row per upstream marketplace the store mirrors into its own catalog.

CREATE TABLE upstream_source (
    id                UUID PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    marketplace_url   VARCHAR(1024) NOT NULL,
    target_namespace  VARCHAR(63) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync_at      TIMESTAMP(6) WITH TIME ZONE,
    last_sync_ok      BOOLEAN,
    last_error        VARCHAR(1000)
);
