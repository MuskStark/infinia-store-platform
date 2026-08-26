package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.FavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteJpaRepository extends JpaRepository<FavoriteEntity, UUID> {

    List<FavoriteEntity> findByUserIdOrderByAddedAtDesc(UUID userId);

    boolean existsByUserIdAndListingId(UUID userId, UUID listingId);

    void deleteByUserIdAndListingId(UUID userId, UUID listingId);

    long countByListingId(UUID listingId);
}
