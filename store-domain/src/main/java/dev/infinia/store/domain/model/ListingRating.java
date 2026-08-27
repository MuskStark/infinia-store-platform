package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** A user rating on a listing (design §12.4: 商品详情 评价). One per user per listing. */
public record ListingRating(
        UUID id,
        UUID listingId,
        UUID userId,
        int stars,
        String comment,
        Instant createdAt,
        Instant updatedAt) {
}
