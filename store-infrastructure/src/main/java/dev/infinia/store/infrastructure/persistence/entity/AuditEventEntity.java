package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEventEntity {
    @Id
    public UUID id;
    @Column(name = "actor_type", nullable = false)
    public String actorType;
    @Column(name = "actor_id")
    public String actorId;
    @Column(name = "action", nullable = false)
    public String action;
    @Column(name = "resource_type", nullable = false)
    public String resourceType;
    @Column(name = "resource_id")
    public String resourceId;
    @Column(name = "before_summary")
    public String beforeSummary;
    @Column(name = "after_summary")
    public String afterSummary;
    @Column(name = "ip_hash")
    public String ipHash;
    @Column(name = "trace_id")
    public String traceId;
    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}
