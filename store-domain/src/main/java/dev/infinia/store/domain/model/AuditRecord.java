package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Append-only, non-repudiable audit event (design §14.3). */
public record AuditRecord(UUID id, String actorType, String actorId, String action,
        String resourceType, String resourceId, String beforeSummary, String afterSummary,
        String ipHash, String traceId, Instant occurredAt) {
}
