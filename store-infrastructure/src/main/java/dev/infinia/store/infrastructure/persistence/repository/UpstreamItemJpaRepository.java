package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UpstreamItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UpstreamItemJpaRepository extends JpaRepository<UpstreamItemEntity, UUID> {

    Optional<UpstreamItemEntity> findBySourceIdAndExternalIdAndContentSha256(
            UUID sourceId, String externalId, String contentSha256);

    Optional<UpstreamItemEntity> findFirstBySourceIdAndExternalIdOrderByLastSeenAtDesc(
            UUID sourceId, String externalId);

    Optional<UpstreamItemEntity> findFirstByListingIdOrderByLastSeenAtDesc(UUID listingId);

    List<UpstreamItemEntity> findBySourceId(UUID sourceId);
}
