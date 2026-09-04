package dev.infinia.store.domain.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-status ports (需求：store 服务监控页): daily per-component uptime
 * samples power the 90-day history bars and auto-detected incidents power the
 * "Past Incidents" feed of the public status page.
 */
public final class StatusRepositories {

    private StatusRepositories() {}

    /** One component's aggregated probe outcome for one UTC day. */
    public record DailySample(String component, LocalDate day, long ok, long degraded, long down) {}

    public interface UptimeRepository {

        /** Upserts the sample into the component's bucket for that day. */
        void record(DailySample sample);

        /** All days with data for a component from {@code from} (inclusive). */
        List<DailySample> findSince(String component, LocalDate from);

        /** Drops buckets strictly before the given day (observation-window housekeeping). */
        int pruneBefore(LocalDate day);
    }

    /**
     * An incident is opened automatically when a probed component starts
     * failing and resolved when its probe recovers — no manual tooling needed.
     */
    public record Incident(UUID id, String component, String title, String impact,
            String status, Instant startedAt, Instant resolvedAt, Instant updatedAt) {}

    public interface IncidentRepository {

        Optional<Incident> findUnresolvedByComponent(String component);

        Incident save(Incident incident);

        List<Incident> findRecent(int limit);
    }
}
