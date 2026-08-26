package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional install telemetry (design §11.1 / ADR-009). Telemetry is never the
 * source of truth for local install state.
 */
public record InstallEventRecord(UUID id, String idempotencyKey, UUID userId, String deviceId,
        String coordinate, String version, String type, String action, String outcome,
        String hostVersion, String os, String arch, Instant occurredAt, Instant receivedAt) {
}
