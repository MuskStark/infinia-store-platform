-- Infinia Store Platform — community moderation and organization administration
-- (design §7.3 org RBAC surface, §12.4 listing ratings / abuse reports, §14.3 audit reads).

CREATE TABLE listing_rating (
    id         UUID PRIMARY KEY,
    listing_id UUID NOT NULL,
    user_id    UUID NOT NULL,
    stars      SMALLINT NOT NULL,
    comment    VARCHAR(2000),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_listing_rating UNIQUE (listing_id, user_id),
    CONSTRAINT ck_listing_rating_stars CHECK (stars BETWEEN 1 AND 5)
);
CREATE INDEX ix_listing_rating_listing ON listing_rating (listing_id);

CREATE TABLE listing_report (
    id              UUID PRIMARY KEY,
    listing_id      UUID NOT NULL,
    reporter_id     UUID NOT NULL,
    reason          VARCHAR(64) NOT NULL,
    details         VARCHAR(2000),
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution_note VARCHAR(1000),
    resolved_by     UUID,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX ix_listing_report_status ON listing_report (status, created_at);
