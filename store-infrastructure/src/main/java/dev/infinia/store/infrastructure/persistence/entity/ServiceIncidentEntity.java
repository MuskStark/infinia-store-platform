package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Persisted auto-detected incident of the service-status page. */
@Entity
@Table(name = "service_incident")
public class ServiceIncidentEntity {
    @Id
    public UUID incidentId;
    @Column(nullable = false)
    public String component;
    @Column(nullable = false)
    public String title;
    @Column(nullable = false)
    public String impact;
    @Column(nullable = false)
    public String status;
    @Column(name = "started_at", nullable = false)
    public Instant startedAt;
    @Column(name = "resolved_at")
    public Instant resolvedAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
