package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An upstream marketplace the store aggregates (design §2.1): the store fetches
 * its catalog, materializes entries as reviewed store listings and publishes
 * them — so hosts configure only the store instead of each upstream themselves.
 */
public record UpstreamSource(
        UUID id,
        String name,
        String marketplaceUrl,
        String targetNamespace,
        boolean enabled,
        Instant lastSyncAt,
        Boolean lastSyncOk,
        String lastError) {
}
