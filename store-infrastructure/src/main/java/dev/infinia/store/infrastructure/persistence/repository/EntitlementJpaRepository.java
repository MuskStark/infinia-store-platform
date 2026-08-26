package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.EntitlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntitlementJpaRepository extends JpaRepository<EntitlementEntity, UUID> {

    List<EntitlementEntity> findByUserId(UUID userId);

    Optional<EntitlementEntity> findByUserIdAndListingId(UUID userId, UUID listingId);
}
