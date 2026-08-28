package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Per-item provenance: one upstream entry under one content digest (plan §4.1). */
public record UpstreamItem(
        UUID id,
        UUID sourceId,
        String externalId,
        UUID listingId,
        String sourceUrl,
        String sourcePath,
        String ref,
        String commitSha,
        String upstreamVersion,
        String contentSha256,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant removedAt) {
}
