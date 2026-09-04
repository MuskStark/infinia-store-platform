-- Rotating per-install refresh credentials for the FengYu desktop client
-- (design §7.2): the public OAuth client gets no refresh token from the
-- authorization server, so long-lived desktop sessions ride a store-managed
-- credential instead. Only the SHA-256 hash of each token is persisted — a
-- database leak must not leak live bearer credentials. Rows are single-use
-- (consumed_at set on rotation) and kept after consumption so a replay can be
-- detected and revoke the whole session family.
CREATE TABLE refresh_token (
    token_hash        VARCHAR(64) PRIMARY KEY,
    session_id        UUID NOT NULL,
    user_id           UUID NOT NULL,
    client_id         VARCHAR(128) NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    absolute_deadline TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at       TIMESTAMP(6) WITH TIME ZONE,
    revoked           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_refresh_token_session ON refresh_token (session_id);
