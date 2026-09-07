package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-item provenance: one upstream entry under one content digest (plan §4.1).
 * {@code adapterType} records the concrete adapter the sync resolved for an AUTO
 * source, so the download path replays the same discovery decision instead of
 * re-probing (and possibly disagreeing with) the upstream document.
 */
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
        String adapterType,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant removedAt) {

    /** Legacy constructor for rows persisted before the adapter type was recorded. */
    public UpstreamItem(UUID id, UUID sourceId, String externalId, UUID listingId,
            String sourceUrl, String sourcePath, String ref, String commitSha,
            String upstreamVersion, String contentSha256, Instant firstSeenAt,
            Instant lastSeenAt, Instant removedAt) {
        this(id, sourceId, externalId, listingId, sourceUrl, sourcePath, ref, commitSha,
                upstreamVersion, contentSha256, null, firstSeenAt, lastSeenAt, removedAt);
    }
}
