package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {
    @Id
    public UUID id;
    @Column(name = "aggregate_type", nullable = false)
    public String aggregateType;
    @Column(name = "aggregate_id", nullable = false)
    public String aggregateId;
    @Column(name = "type", nullable = false)
    public String type;
    @Column(name = "payload", nullable = false)
    public String payload;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "attempts", nullable = false)
    public int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    public Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
