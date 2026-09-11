package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_membership")
public class UserMembershipEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "level", nullable = false)
    public int level;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
