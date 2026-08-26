package dev.infinia.store.contract.event;

import java.util.List;

/**
 * Event types and payload records written to the transactional outbox (design §5.2,
 * §8.2 step 9). Payloads are contract-stable so workers and webhooks can evolve
 * independently from the application.
 */
public final class StoreEventPayloads {

    private StoreEventPayloads() {}

    public static final String RELEASE_PUBLISHED = "release.published";
    public static final String RELEASE_REJECTED = "release.rejected";
    public static final String RELEASE_YANKED = "release.yanked";
    public static final String RELEASE_QUARANTINED = "release.quarantined";
    public static final String LISTING_CREATED = "listing.created";
    public static final String USER_REGISTERED = "user.registered";

    public record ReleasePublished(String coordinate, String releaseId, String version,
            String channel, String publishedAt, String envelopeJson) {}

    public record ReleaseRejected(String coordinate, String releaseId, String reason,
            List<String> ruleViolations) {}

    public record ReleaseYanked(String coordinate, String releaseId, String reason) {}

    public record ReleaseQuarantined(String coordinate, String releaseId, String reason) {}

    public record ListingCreated(String coordinate, String type, String name) {}
}
