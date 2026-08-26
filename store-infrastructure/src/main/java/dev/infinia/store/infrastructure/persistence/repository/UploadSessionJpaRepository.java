package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UploadSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UploadSessionJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {

    List<UploadSessionEntity> findByReleaseId(UUID releaseId);
}
