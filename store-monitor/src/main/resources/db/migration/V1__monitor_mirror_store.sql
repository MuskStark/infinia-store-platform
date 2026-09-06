-- Monitor mirror store (ADR-011): the last snapshot served by the store, the
-- external-reachability day buckets and the monitor's own incidents. This is
-- the monitoring host's private H2 database — never shared with the store.

CREATE TABLE mirror_snapshot (
    id            INT NOT NULL PRIMARY KEY,
    fetched_at    TIMESTAMP NOT NULL,
    page_json     CLOB NOT NULL,
    incidents_json CLOB NOT NULL
);

CREATE TABLE external_uptime_day (
    component VARCHAR(64) NOT NULL,
    sample_day DATE NOT NULL,
    ok        BIGINT NOT NULL,
    degraded  BIGINT NOT NULL,
    down      BIGINT NOT NULL,
    PRIMARY KEY (component, sample_day)
);

CREATE TABLE monitor_incident (
    id          UUID NOT NULL PRIMARY KEY,
    component   VARCHAR(64) NOT NULL,
    title       VARCHAR(256) NOT NULL,
    impact      VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    started_at  TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL
);
