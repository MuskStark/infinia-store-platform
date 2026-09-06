package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UploadSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UploadSessionJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {

    List<UploadSessionEntity> findByReleaseId(UUID releaseId);

    /**
     * Atomic PENDING → COMPLETED claim: exactly one concurrent replay of a
     * presigned upload URL affects a row; losers get 0.
     */
    @Modifying
    @Query("UPDATE UploadSessionEntity s SET s.status = 'COMPLETED'"
            + " WHERE s.id = :id AND s.status = 'PENDING'")
    int claimForCompletion(UUID id);
}
