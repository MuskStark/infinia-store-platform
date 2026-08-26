package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Transactional outbox entry (design §5.2). */
public record OutboxRecord(UUID id, String aggregateType, String aggregateId, String type,
        String payloadJson, String status, int attempts, Instant nextAttemptAt, Instant createdAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_FAILED = "FAILED";
}
