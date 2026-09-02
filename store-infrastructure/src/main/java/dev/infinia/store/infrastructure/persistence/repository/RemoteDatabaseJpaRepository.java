package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.RemoteDatabaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemoteDatabaseJpaRepository extends JpaRepository<RemoteDatabaseEntity, UUID> {

    List<RemoteDatabaseEntity> findAllByOrderByNameAsc();

    Optional<RemoteDatabaseEntity> findByName(String name);

    Optional<RemoteDatabaseEntity> findByEnabledTrue();
}
