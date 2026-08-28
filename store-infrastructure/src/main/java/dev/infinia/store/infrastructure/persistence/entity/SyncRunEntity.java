package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_run")
public class SyncRunEntity {
    @Id
    public UUID id;
    @Column(name = "source_id", nullable = false)
    public UUID sourceId;
    @Column(name = "started_at", nullable = false)
    public Instant startedAt;
    @Column(name = "finished_at")
    public Instant finishedAt;
    @Column(nullable = false)
    public Integer imported;
    @Column(nullable = false)
    public Integer skipped;
    @Column(nullable = false)
    public Integer failed;
    @Column(nullable = false, length = 16)
    public String status;
    @Column(length = 4000)
    public String errors;
}
