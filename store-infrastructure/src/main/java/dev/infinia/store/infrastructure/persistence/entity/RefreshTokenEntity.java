package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted row of the desktop refresh-credential chain (design §7.2): only the
 * SHA-256 hash of the token is stored, rows are single-use and survive
 * consumption so a replay can be detected and revoke the session family.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshTokenEntity {
    @Id
    public String tokenHash;
    @Column(name = "session_id", nullable = false)
    public UUID sessionId;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "client_id", nullable = false)
    public String clientId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "absolute_deadline", nullable = false)
    public Instant absoluteDeadline;
    @Column(name = "consumed_at")
    public Instant consumedAt;
    @Column(name = "revoked", nullable = false)
    public boolean revoked;
}
