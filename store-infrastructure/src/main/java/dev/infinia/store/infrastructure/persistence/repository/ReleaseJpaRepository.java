package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseJpaRepository extends JpaRepository<ReleaseEntity, UUID> {

    List<ReleaseEntity> findByListingId(UUID listingId);

    Optional<ReleaseEntity> findByListingIdAndVersion(UUID listingId, String version);

    List<ReleaseEntity> findByListingIdIn(List<UUID> listingIds);

    List<ReleaseEntity> findByStatusIn(List<String> statuses);

    List<ReleaseEntity> findByStatus(String status);

    @Query("select distinct r from ReleaseEntity r join r.artifacts a"
            + " where a.blobKey = :blobKey")
    Optional<ReleaseEntity> findByArtifactBlobKey(@Param("blobKey") String blobKey);
}

