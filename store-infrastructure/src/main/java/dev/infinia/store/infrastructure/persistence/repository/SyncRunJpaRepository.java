package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.SyncRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SyncRunJpaRepository extends JpaRepository<SyncRunEntity, UUID> {
}
