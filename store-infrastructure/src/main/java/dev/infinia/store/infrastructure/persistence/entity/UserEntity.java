package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "store_user")
public class UserEntity {
    @Id
    public UUID id;
    @Column(name = "email", nullable = false)
    public String email;
    @Column(name = "email_normalized", nullable = false)
    public String emailNormalized;
    @Column(name = "display_name")
    public String displayName;
    /** Comma-separated UserRole values (portable across PostgreSQL and H2). */
    @Column(name = "roles", nullable = false)
    public String roles;
    @Column(name = "status", nullable = false)
    public String status;
    /** Bee ladder position 0..4 (蜜蜂等级); gates level-restricted listings. */
    @Column(name = "bee_level", nullable = false)
    public int beeLevel;
    @Column(name = "mfa_enabled", nullable = false)
    public boolean mfaEnabled;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "last_login_at")
    public Instant lastLoginAt;
}
