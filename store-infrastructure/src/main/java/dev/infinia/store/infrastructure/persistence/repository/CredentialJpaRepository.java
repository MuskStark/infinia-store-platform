package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.CredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CredentialJpaRepository extends JpaRepository<CredentialEntity, UUID> {

    Optional<CredentialEntity> findByUserIdAndType(UUID userId, String type);
}
