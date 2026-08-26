package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.SigningKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SigningKeyJpaRepository extends JpaRepository<SigningKeyEntity, String> {

    List<SigningKeyEntity> findByOwnerTypeAndStatus(String ownerType, String status);
}
