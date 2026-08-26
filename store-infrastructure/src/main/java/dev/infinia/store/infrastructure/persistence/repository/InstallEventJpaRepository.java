package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.InstallEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InstallEventJpaRepository extends JpaRepository<InstallEventEntity, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<InstallEventEntity> findTop100ByUserIdOrderByReceivedAtDesc(UUID userId);
}
