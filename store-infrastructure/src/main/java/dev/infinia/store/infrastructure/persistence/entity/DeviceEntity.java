package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device")
public class DeviceEntity {
    @Id
    public UUID id;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "public_id", nullable = false)
    public String publicId;
    @Column(name = "name")
    public String name;
    @Column(name = "platform")
    public String platform;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "last_seen_at")
    public Instant lastSeenAt;
    @Column(name = "revoked", nullable = false)
    public boolean revoked;
}
