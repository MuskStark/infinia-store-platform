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

    /** Published/deprecated releases of all listings of one type (compat catalogs). */
    @Query("select r from ReleaseEntity r, ListingEntity l"
            + " where r.listingId = l.id and l.type = :type and r.status in :statuses")
    List<ReleaseEntity> findVisibleByType(@Param("type") String type,
            @Param("statuses") List<String> statuses);

    @Query("select distinct r from ReleaseEntity r join r.artifacts a"
            + " where a.blobKey = :blobKey")
    Optional<ReleaseEntity> findByArtifactBlobKey(@Param("blobKey") String blobKey);
}

