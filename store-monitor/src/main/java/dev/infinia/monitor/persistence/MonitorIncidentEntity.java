package dev.infinia.monitor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An incident the monitor itself opened — today that is the store's external
 * reachability failing (the one failure the store can never report about
 * itself). Mirrored store incidents are not copied: they stay in the store's
 * feed and are merged only for display.
 */
@Entity
@Table(name = "monitor_incident")
public class MonitorIncidentEntity {

    @Id
    public UUID id;

    @Column(nullable = false)
    public String component;

    @Column(nullable = false)
    public String title;

    /** outage | degraded */
    @Column(nullable = false)
    public String impact;

    /** investigating | resolved */
    @Column(nullable = false)
    public String status;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt;

    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
