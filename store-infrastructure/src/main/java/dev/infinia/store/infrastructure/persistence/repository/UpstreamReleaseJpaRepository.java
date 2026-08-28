package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.UpstreamReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UpstreamReleaseJpaRepository extends JpaRepository<UpstreamReleaseEntity, UUID> {

    List<UpstreamReleaseEntity> findByUpstreamItemId(UUID upstreamItemId);
}
