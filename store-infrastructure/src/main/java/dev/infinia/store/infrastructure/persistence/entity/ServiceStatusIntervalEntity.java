package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One recorded stretch of a component's confirmed state — the interval
 * statistics backing the time-based availability and coverage of the status
 * page. {@code endedAt} is null while the state is ongoing.
 */
@Entity
@Table(name = "service_status_interval")
public class ServiceStatusIntervalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String component;
    @Column(nullable = false)
    public String indicator;
    @Column(name = "started_at", nullable = false)
    public Instant startedAt;
    @Column(name = "ended_at")
    public Instant endedAt;
    /** Where the observation came from, e.g. store-sampler. */
    @Column(nullable = false)
    public String source;
}
