package dev.infinia.monitor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One recorded stretch of a component's confirmed state; {@code endedAt}
 *  is null while the state is ongoing. */
@Entity
@Table(name = "status_interval")
public class StatusIntervalEntity {

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
    /** Where the observation came from, e.g. external-probe. */
    @Column(nullable = false)
    public String source;
}
