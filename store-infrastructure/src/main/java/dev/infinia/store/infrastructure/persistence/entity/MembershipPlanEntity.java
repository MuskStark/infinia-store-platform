package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership_plan")
public class MembershipPlanEntity {
    @Id
    public UUID id;
    @Column(name = "bee_level", nullable = false)
    public int beeLevel;
    @Column(name = "duration_days", nullable = false)
    public int durationDays;
    @Column(name = "price_fen", nullable = false)
    public long priceFen;
    @Column(name = "active", nullable = false)
    public boolean active;
    @Column(name = "sort", nullable = false)
    public int sort;
    /** Plan-specific checkout link (e.g. a Buy Me a Coffee Extra); null = gateway-built URL. */
    @Column(name = "external_url")
    public String externalUrl;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
