-- Infinia Store Platform — multi-upstream aggregation (aggregation plan §4/§9):
-- adapter-typed sources, per-item provenance tracking, sync runs.

ALTER TABLE upstream_source ADD COLUMN adapter_type VARCHAR(40);
ALTER TABLE upstream_source ADD COLUMN config_json VARCHAR(4000);
ALTER TABLE upstream_source ADD COLUMN sync_cursor VARCHAR(512);
ALTER TABLE upstream_source ADD COLUMN etag VARCHAR(256);
ALTER TABLE upstream_source ADD COLUMN trust_policy VARCHAR(32) NOT NULL DEFAULT 'SCAN_FIRST';

CREATE TABLE upstream_item (
    id                 UUID PRIMARY KEY,
    source_id          UUID NOT NULL,
    external_id        VARCHAR(512) NOT NULL,
    listing_id         UUID,
    source_url         VARCHAR(1024),
    source_path        VARCHAR(512),
    ref                VARCHAR(128),
    commit_sha         VARCHAR(64),
    upstream_version   VARCHAR(64),
    content_sha256     VARCHAR(64) NOT NULL,
    first_seen_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_seen_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    removed_at         TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ux_upstream_item UNIQUE (source_id, external_id, content_sha256)
);
CREATE INDEX ix_upstream_item_source ON upstream_item (source_id);

CREATE TABLE upstream_release (
    id                  UUID PRIMARY KEY,
    upstream_item_id    UUID NOT NULL,
    listing_release_id  UUID,
    source_commit_sha   VARCHAR(64),
    source_version      VARCHAR(64),
    normalized_sha256   VARCHAR(64) NOT NULL,
    sync_run_id         UUID,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_upstream_release_item FOREIGN KEY (upstream_item_id)
        REFERENCES upstream_item (id)
);
CREATE INDEX ix_upstream_release_item ON upstream_release (upstream_item_id);

CREATE TABLE sync_run (
    id           UUID PRIMARY KEY,
    source_id    UUID NOT NULL,
    started_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at  TIMESTAMP(6) WITH TIME ZONE,
    imported     INTEGER NOT NULL DEFAULT 0,
    skipped      INTEGER NOT NULL DEFAULT 0,
    failed       INTEGER NOT NULL DEFAULT 0,
    status       VARCHAR(16) NOT NULL,
    errors       VARCHAR(4000)
);
