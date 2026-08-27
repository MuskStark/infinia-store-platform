package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ListingReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ListingReportJpaRepository extends JpaRepository<ListingReportEntity, UUID> {

    List<ListingReportEntity> findTop100ByStatusOrderByCreatedAtDesc(String status);

    boolean existsByReporterIdAndListingIdAndStatus(UUID reporterId, UUID listingId, String status);
}
