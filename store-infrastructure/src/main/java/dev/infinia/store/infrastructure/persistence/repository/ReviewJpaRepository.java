package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewJpaRepository extends JpaRepository<ReviewEntity, UUID> {

    List<ReviewEntity> findByReleaseIdOrderBySubmittedAtDesc(UUID releaseId);

    Optional<ReviewEntity> findTopByReleaseIdOrderBySubmittedAtDesc(UUID releaseId);

    List<ReviewEntity> findTop100ByStatusOrderBySubmittedAtDesc(String status);
}
