package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credential")
public class CredentialEntity {
    @Id
    public UUID id;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "type", nullable = false)
    public String type;
    @Column(name = "secret_hash", nullable = false)
    public String secretHash;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
