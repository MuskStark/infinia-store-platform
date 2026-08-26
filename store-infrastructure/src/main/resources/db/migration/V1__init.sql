-- Infinia Store Platform — initial schema (design §11.1).
-- Written to run on both PostgreSQL and H2 (PostgreSQL compatibility mode):
-- no array types, no partial indexes, no DB-generated UUIDs.

CREATE TABLE store_user (
    id              UUID PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    display_name    VARCHAR(64),
    roles           VARCHAR(255) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    mfa_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_login_at   TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ux_store_user_email UNIQUE (email_normalized)
);

CREATE TABLE credential (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    type        VARCHAR(16) NOT NULL,
    secret_hash VARCHAR(512) NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_credential_user FOREIGN KEY (user_id) REFERENCES store_user (id)
);

CREATE TABLE user_session (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL,
    client_id     VARCHAR(128),
    kind          VARCHAR(32) NOT NULL,
    device_id     UUID,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at  TIMESTAMP(6) WITH TIME ZONE,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    remote_ip_hash VARCHAR(128)
);
CREATE INDEX ix_user_session_user ON user_session (user_id);

CREATE TABLE organization (
    id            UUID PRIMARY KEY,
    slug          VARCHAR(63) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    owner_user_id UUID NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_organization_slug UNIQUE (slug)
);

CREATE TABLE organization_member (
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    role            VARCHAR(32) NOT NULL,
    joined_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_organization_member PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_org_member_org FOREIGN KEY (organization_id) REFERENCES organization (id)
);

CREATE TABLE namespace (
    id              UUID PRIMARY KEY,
    name            VARCHAR(63) NOT NULL,
    owner_user_id   UUID,
    organization_id UUID,
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_namespace_name UNIQUE (name),
    CONSTRAINT ck_namespace_owner CHECK (owner_user_id IS NOT NULL OR organization_id IS NOT NULL)
);

CREATE TABLE listing (
    id                UUID PRIMARY KEY,
    namespace_id      UUID NOT NULL,
    namespace         VARCHAR(63) NOT NULL,
    slug              VARCHAR(63) NOT NULL,
    type              VARCHAR(16) NOT NULL,
    visibility        VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    category          VARCHAR(64),
    tags              VARCHAR(1000),
    icon_url          VARCHAR(1024),
    screenshots       VARCHAR(4000),
    default_channel   VARCHAR(16) NOT NULL,
    publisher_user_id UUID NOT NULL,
    organization_id   UUID,
    downloads         BIGINT NOT NULL DEFAULT 0,
    favorite_count    BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_listing UNIQUE (namespace_id, slug, type),
    CONSTRAINT fk_listing_namespace FOREIGN KEY (namespace_id) REFERENCES namespace (id)
);
CREATE INDEX ix_listing_type ON listing (type);
CREATE INDEX ix_listing_status ON listing (status, visibility);

CREATE TABLE listing_i18n (
    listing_id         UUID NOT NULL,
    locale             VARCHAR(8) NOT NULL,
    name               VARCHAR(100) NOT NULL,
    summary            VARCHAR(500),
    description_md     TEXT,
    changelog_md       TEXT,
    CONSTRAINT pk_listing_i18n PRIMARY KEY (listing_id, locale),
    CONSTRAINT fk_listing_i18n FOREIGN KEY (listing_id) REFERENCES listing (id) ON DELETE CASCADE
);

CREATE TABLE release (
    id               UUID PRIMARY KEY,
    listing_id       UUID NOT NULL,
    version          VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    channel          VARCHAR(16) NOT NULL,
    published_at     TIMESTAMP(6) WITH TIME ZONE,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    requires_host    VARCHAR(128),
    license          VARCHAR(64),
    source_url       VARCHAR(1024),
    changelog_md     TEXT,
    rollout_percent  INTEGER NOT NULL DEFAULT 100,
    CONSTRAINT ux_release_version UNIQUE (listing_id, version),
    CONSTRAINT fk_release_listing FOREIGN KEY (listing_id) REFERENCES listing (id) ON DELETE CASCADE
);
CREATE INDEX ix_release_listing ON release (listing_id);
CREATE INDEX ix_release_status ON release (status, channel);

CREATE TABLE release_artifact (
    release_id  UUID NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    platform    VARCHAR(16) NOT NULL,
    arch        VARCHAR(16) NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    size_bytes  BIGINT NOT NULL,
    sha256      VARCHAR(64) NOT NULL,
    signature   TEXT,
    key_id      VARCHAR(64),
    blob_key    VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(128),
    CONSTRAINT pk_release_artifact PRIMARY KEY (release_id, platform, arch, kind),
    CONSTRAINT fk_artifact_release FOREIGN KEY (release_id) REFERENCES release (id) ON DELETE CASCADE
);

CREATE TABLE release_dependency (
    release_id  UUID NOT NULL,
    coordinate  VARCHAR(255) NOT NULL,
    range_expr  VARCHAR(128) NOT NULL,
    optional    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_release_dependency PRIMARY KEY (release_id, coordinate),
    CONSTRAINT fk_dependency_release FOREIGN KEY (release_id) REFERENCES release (id) ON DELETE CASCADE
);

CREATE TABLE release_permission (
    release_id    UUID NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    scope         VARCHAR(255) NOT NULL,
    required      BOOLEAN NOT NULL DEFAULT TRUE,
    reason        VARCHAR(500),
    CONSTRAINT pk_release_permission PRIMARY KEY (release_id, permission_id),
    CONSTRAINT fk_permission_release FOREIGN KEY (release_id) REFERENCES release (id) ON DELETE CASCADE
);

CREATE TABLE review (
    id           UUID PRIMARY KEY,
    release_id   UUID NOT NULL,
    listing_id   UUID NOT NULL,
    status       VARCHAR(32) NOT NULL,
    reviewer_id  UUID,
    notes        TEXT,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    decided_at   TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX ix_review_status ON review (status);

CREATE TABLE review_finding (
    review_id UUID NOT NULL,
    severity  VARCHAR(16) NOT NULL,
    rule      VARCHAR(64) NOT NULL,
    message   VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_review_finding PRIMARY KEY (review_id, rule, message),
    CONSTRAINT fk_finding_review FOREIGN KEY (review_id) REFERENCES review (id) ON DELETE CASCADE
);

CREATE TABLE upload_session (
    id            UUID PRIMARY KEY,
    release_id    UUID NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    kind          VARCHAR(16) NOT NULL,
    platform      VARCHAR(16) NOT NULL,
    arch          VARCHAR(16) NOT NULL,
    declared_size BIGINT NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL,
    expires_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    blob_key      VARCHAR(255),
    sha256        VARCHAR(64),
    mime_type     VARCHAR(128)
);

CREATE TABLE device (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    public_id   VARCHAR(128) NOT NULL,
    name        VARCHAR(128),
    platform    VARCHAR(32),
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP(6) WITH TIME ZONE,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ux_device_public UNIQUE (user_id, public_id)
);

CREATE TABLE favorite (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    listing_id UUID NOT NULL,
    added_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_favorite UNIQUE (user_id, listing_id)
);

CREATE TABLE entitlement (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    listing_id  UUID NOT NULL,
    free        BOOLEAN NOT NULL DEFAULT TRUE,
    acquired_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_entitlement UNIQUE (user_id, listing_id)
);

CREATE TABLE install_event (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL,
    user_id          UUID,
    device_id        VARCHAR(128),
    coordinate       VARCHAR(255) NOT NULL,
    version          VARCHAR(64) NOT NULL,
    type             VARCHAR(16),
    action           VARCHAR(32) NOT NULL,
    outcome          VARCHAR(32) NOT NULL,
    host_version     VARCHAR(64),
    os               VARCHAR(32),
    arch             VARCHAR(32),
    occurred_at      TIMESTAMP(6) WITH TIME ZONE,
    received_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_install_event_key UNIQUE (idempotency_key)
);
CREATE INDEX ix_install_event_user ON install_event (user_id, received_at);

CREATE TABLE signing_key (
    key_id            VARCHAR(64) PRIMARY KEY,
    algorithm         VARCHAR(32) NOT NULL,
    public_key_base64 TEXT NOT NULL,
    owner_type        VARCHAR(32) NOT NULL,
    owner_ref         VARCHAR(128),
    status            VARCHAR(16) NOT NULL,
    valid_from        TIMESTAMP(6) WITH TIME ZONE,
    valid_to          TIMESTAMP(6) WITH TIME ZONE
);

CREATE TABLE audit_event (
    id             UUID PRIMARY KEY,
    actor_type     VARCHAR(32) NOT NULL,
    actor_id       VARCHAR(128),
    action         VARCHAR(64) NOT NULL,
    resource_type  VARCHAR(64) NOT NULL,
    resource_id    VARCHAR(128),
    before_summary TEXT,
    after_summary  TEXT,
    ip_hash        VARCHAR(128),
    trace_id       VARCHAR(64),
    occurred_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_audit_event_time ON audit_event (occurred_at);

CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    type            VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_outbox_dispatch ON outbox_event (status, next_attempt_at);

CREATE TABLE webhook (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    url             VARCHAR(1024) NOT NULL,
    secret          VARCHAR(128) NOT NULL,
    events          VARCHAR(1000) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
