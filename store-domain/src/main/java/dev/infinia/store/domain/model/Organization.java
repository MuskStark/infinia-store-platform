package dev.infinia.store.domain.model;

import dev.infinia.store.contract.type.UserRole;

import java.time.Instant;
import java.util.UUID;

/** Publishing organization (design §7.1). */
public record Organization(UUID id, String slug, String name, UUID ownerUserId, Instant createdAt) {

    public record Member(UUID organizationId, UUID userId, UserRole role, Instant joinedAt) {}
}
