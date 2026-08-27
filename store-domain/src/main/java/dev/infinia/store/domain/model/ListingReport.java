package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An abuse report filed against a listing (design §12.4 管理: 举报). Resolved by a
 * platform admin as ACTIONED (e.g. release quarantined) or DISMISSED.
 */
public record ListingReport(
        UUID id,
        UUID listingId,
        UUID reporterId,
        String reason,
        String details,
        String status,
        String resolutionNote,
        UUID resolvedBy,
        Instant createdAt,
        Instant resolvedAt) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACTIONED = "ACTIONED";
    public static final String STATUS_DISMISSED = "DISMISSED";
}
