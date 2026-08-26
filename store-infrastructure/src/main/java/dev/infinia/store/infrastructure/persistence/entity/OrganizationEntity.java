package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class OrganizationEntity {
    @Id
    public UUID id;
    @Column(name = "slug", nullable = false)
    public String slug;
    @Column(name = "name", nullable = false)
    public String name;
    @Column(name = "owner_user_id", nullable = false)
    public UUID ownerUserId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
