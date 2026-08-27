package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.ListingRating;
import dev.infinia.store.domain.port.LibraryRepositories;
import dev.infinia.store.infrastructure.persistence.entity.ListingRatingEntity;
import dev.infinia.store.infrastructure.persistence.repository.ListingRatingJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RatingAdapter implements LibraryRepositories.RatingRepository {

    private final ListingRatingJpaRepository jpa;

    public RatingAdapter(ListingRatingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void upsert(ListingRating rating) {
        ListingRatingEntity e = jpa.findByListingIdAndUserId(rating.listingId(), rating.userId())
                .orElseGet(ListingRatingEntity::new);
        e.id = rating.id();
        e.listingId = rating.listingId();
        e.userId = rating.userId();
        e.stars = (short) rating.stars();
        e.comment = rating.comment();
        e.createdAt = rating.createdAt();
        e.updatedAt = rating.updatedAt();
        jpa.save(e);
    }

    @Override
    public List<ListingRating> findByListing(UUID listingId, int limit) {
        return jpa.findTop50ByListingIdOrderByUpdatedAtDesc(listingId).stream()
                .limit(limit)
                .map(e -> new ListingRating(e.id, e.listingId, e.userId, e.stars, e.comment,
                        e.createdAt, e.updatedAt))
                .toList();
    }

    @Override
    public Optional<ListingRating> findByUserAndListing(UUID userId, UUID listingId) {
        return jpa.findByListingIdAndUserId(listingId, userId)
                .map(e -> new ListingRating(e.id, e.listingId, e.userId, e.stars, e.comment,
                        e.createdAt, e.updatedAt));
    }

    @Override
    public LibraryRepositories.RatingSummary summarize(UUID listingId) {
        List<ListingRatingEntity> all = jpa.findAllByListingId(listingId);
        if (all.isEmpty()) {
            return new LibraryRepositories.RatingSummary(0, 0d);
        }
        double total = all.stream().mapToInt(r -> r.stars).sum();
        return new LibraryRepositories.RatingSummary(all.size(), total / all.size());
    }
}
