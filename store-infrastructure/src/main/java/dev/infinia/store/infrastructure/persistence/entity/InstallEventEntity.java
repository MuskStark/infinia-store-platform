package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "install_event")
public class InstallEventEntity {
    @Id
    public UUID id;
    @Column(name = "idempotency_key", nullable = false)
    public String idempotencyKey;
    @Column(name = "user_id")
    public UUID userId;
    @Column(name = "device_id")
    public String deviceId;
    @Column(name = "coordinate", nullable = false)
    public String coordinate;
    @Column(name = "version", nullable = false)
    public String version;
    @Column(name = "type")
    public String type;
    @Column(name = "action", nullable = false)
    public String action;
    @Column(name = "outcome", nullable = false)
    public String outcome;
    @Column(name = "host_version")
    public String hostVersion;
    @Column(name = "os")
    public String os;
    @Column(name = "arch")
    public String arch;
    @Column(name = "occurred_at")
    public Instant occurredAt;
    @Column(name = "received_at", nullable = false)
    public Instant receivedAt;
}
