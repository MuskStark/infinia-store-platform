package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** A host device binding (design §7.1). Revocation invalidates its refresh tokens. */
public record Device(UUID id, UUID userId, String publicId, String name, String platform,
        Instant createdAt, Instant lastSeenAt, boolean revoked) {
}
