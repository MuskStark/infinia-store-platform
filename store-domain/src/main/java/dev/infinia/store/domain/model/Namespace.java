package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Listing name ownership. Namespaces prevent typosquatting and dependency confusion
 * (design §13.1); ownership never transfers implicitly.
 */
public record Namespace(UUID id, String name, UUID ownerUserId, UUID organizationId,
        boolean verified, Instant createdAt) {
}
