package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Session ledger for visibility and revocation (design §7.4). */
@Entity
@Table(name = "user_session")
public class UserSessionEntity {
    @Id
    public UUID id;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "client_id")
    public String clientId;
    @Column(name = "kind", nullable = false)
    public String kind;
    @Column(name = "device_id")
    public UUID deviceId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "last_used_at")
    public Instant lastUsedAt;
    @Column(name = "revoked", nullable = false)
    public boolean revoked;
    @Column(name = "remote_ip_hash")
    public String remoteIpHash;
}
