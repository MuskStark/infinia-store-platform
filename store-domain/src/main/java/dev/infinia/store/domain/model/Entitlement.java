package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Free (and, in the future, paid) grant of a listing to a subject (design §3.1). */
public record Entitlement(UUID id, UUID userId, UUID listingId, boolean free, Instant acquiredAt) {
}
