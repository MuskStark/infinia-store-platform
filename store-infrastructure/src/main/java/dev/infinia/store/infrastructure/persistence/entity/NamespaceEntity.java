package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "namespace")
public class NamespaceEntity {
    @Id
    public UUID id;
    @Column(name = "name", nullable = false)
    public String name;
    @Column(name = "owner_user_id")
    public UUID ownerUserId;
    @Column(name = "organization_id")
    public UUID organizationId;
    @Column(name = "verified", nullable = false)
    public boolean verified;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
