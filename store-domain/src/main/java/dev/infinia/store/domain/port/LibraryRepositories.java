package dev.infinia.store.domain.port;

import dev.infinia.store.domain.model.Entitlement;
import dev.infinia.store.domain.model.InstallEventRecord;
import dev.infinia.store.domain.model.ListingRating;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Library ports: favorites, entitlements and install telemetry (design §7.4). */
public final class LibraryRepositories {

    private LibraryRepositories() {}

    public record FavoriteMark(UUID listingId, Instant addedAt) {}

    public interface FavoriteRepository {
        List<FavoriteMark> findByUserId(UUID userId);

        boolean exists(UUID userId, UUID listingId);

        void add(UUID userId, UUID listingId, Instant addedAt);

        void remove(UUID userId, UUID listingId);

        long countByListing(UUID listingId);
    }

    public interface EntitlementRepository {
        List<Entitlement> findByUserId(UUID userId);

        Optional<Entitlement> findByUserAndListing(UUID userId, UUID listingId);

        void save(Entitlement entitlement);
    }

    public interface InstallEventRepository {
        boolean existsByIdempotencyKey(String idempotencyKey);

        void save(InstallEventRecord event);

        List<InstallEventRecord> findRecentByUserId(UUID userId, int limit);
    }

    /** Aggregate rating counters for one listing (design §12.4). */
    public record RatingSummary(long count, double average) {}

    public interface RatingRepository {
        void upsert(ListingRating rating);

        List<ListingRating> findByListing(UUID listingId, int limit);

        Optional<ListingRating> findByUserAndListing(UUID userId, UUID listingId);

        RatingSummary summarize(UUID listingId);
    }
}
