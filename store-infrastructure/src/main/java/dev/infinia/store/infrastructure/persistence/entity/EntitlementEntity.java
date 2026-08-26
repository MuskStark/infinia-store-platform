package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entitlement")
public class EntitlementEntity {
    @Id
    public UUID id;
    @Column(name = "user_id", nullable = false)
    public UUID userId;
    @Column(name = "listing_id", nullable = false)
    public UUID listingId;
    @Column(name = "free", nullable = false)
    public boolean free;
    @Column(name = "acquired_at", nullable = false)
    public Instant acquiredAt;
}
