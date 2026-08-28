package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** One aggregation run of one upstream source (plan §4). */
public record SyncRun(
        UUID id,
        UUID sourceId,
        Instant startedAt,
        Instant finishedAt,
        int imported,
        int skipped,
        int failed,
        String status,
        String errors) {
}
