package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Organization webhook registered for publishing / review / security events. */
public record WebhookInfo(UUID id, UUID organizationId, String url, String secret,
        List<String> events, boolean active, Instant createdAt) {
}
