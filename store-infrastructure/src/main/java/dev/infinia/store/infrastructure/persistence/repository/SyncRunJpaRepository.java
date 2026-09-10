package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.SyncRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncRunJpaRepository extends JpaRepository<SyncRunEntity, UUID> {

    Optional<SyncRunEntity> findTop1BySourceIdOrderByStartedAtDesc(UUID sourceId);

    List<SyncRunEntity> findBySourceIdOrderByStartedAtDesc(UUID sourceId, Pageable pageable);
}
