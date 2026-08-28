package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Ties a store release to the exact upstream revision it was built from (plan §4.1). */
public record UpstreamRelease(
        UUID id,
        UUID upstreamItemId,
        UUID listingReleaseId,
        String sourceCommitSha,
        String sourceVersion,
        String normalizedSha256,
        UUID syncRunId,
        Instant createdAt) {
}
