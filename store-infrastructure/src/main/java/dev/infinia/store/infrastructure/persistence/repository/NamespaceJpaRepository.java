package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.NamespaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NamespaceJpaRepository extends JpaRepository<NamespaceEntity, UUID> {

    Optional<NamespaceEntity> findByName(String name);

    boolean existsByName(String name);

    List<NamespaceEntity> findByOwnerUserIdOrOrganizationIdIn(UUID ownerUserId, List<UUID> organizationIds);
}
