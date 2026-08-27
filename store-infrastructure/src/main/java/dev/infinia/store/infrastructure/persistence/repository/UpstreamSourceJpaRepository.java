package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UpstreamSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UpstreamSourceJpaRepository extends JpaRepository<UpstreamSourceEntity, UUID> {

    List<UpstreamSourceEntity> findAllByOrderByNameAsc();

    Optional<UpstreamSourceEntity> findByName(String name);
}
