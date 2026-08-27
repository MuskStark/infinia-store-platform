package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ListingRatingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRatingJpaRepository extends JpaRepository<ListingRatingEntity, UUID> {

    List<ListingRatingEntity> findTop50ByListingIdOrderByUpdatedAtDesc(UUID listingId);

    List<ListingRatingEntity> findAllByListingId(UUID listingId);

    Optional<ListingRatingEntity> findByListingIdAndUserId(UUID listingId, UUID userId);
}
