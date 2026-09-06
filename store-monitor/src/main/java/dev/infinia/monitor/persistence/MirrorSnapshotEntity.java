package dev.infinia.monitor.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The single mirrored store snapshot (fixed id 1): the last status page and
 * incident feed the store served, as JSON, with the moment they were fetched.
 * Surviving monitor restarts matters — a fresh boot during a store outage must
 * still render the last known state, not an empty page.
 */
@Entity
@Table(name = "mirror_snapshot")
public class MirrorSnapshotEntity {

    @Id
    public int id = 1;

    public Instant fetchedAt;

    /** StatusDtos.StatusPageDto JSON exactly as the store served it. */
    @Lob
    public String pageJson;

    /** StatusDtos.IncidentDto list JSON exactly as the store served it. */
    @Lob
    public String incidentsJson;
}
