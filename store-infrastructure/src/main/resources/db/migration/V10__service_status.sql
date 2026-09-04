-- Public service-status page (需求：store 服务监控页): per-component daily
-- uptime samples feed the 90-day history bars; incidents are opened/resolved
-- automatically by the health sampler (no manual tooling). Sample buckets are
-- one row per component per UTC day and are pruned by the sampler to keep the
-- observation window (plus headroom) small.
CREATE TABLE service_uptime_day (
    component  VARCHAR(64) NOT NULL,
    sample_day DATE        NOT NULL,
    ok         BIGINT      NOT NULL DEFAULT 0,
    degraded   BIGINT      NOT NULL DEFAULT 0,
    down       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (component, sample_day)
);

CREATE TABLE service_incident (
    incident_id UUID PRIMARY KEY,
    component   VARCHAR(64) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    impact      VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    started_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_service_incident_started ON service_incident (started_at DESC);
CREATE INDEX ix_service_incident_component ON service_incident (component);
