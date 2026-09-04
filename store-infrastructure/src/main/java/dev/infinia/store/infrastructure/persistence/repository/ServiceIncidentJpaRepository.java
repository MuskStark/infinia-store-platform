package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ServiceIncidentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceIncidentJpaRepository extends JpaRepository<ServiceIncidentEntity, UUID> {

    Optional<ServiceIncidentEntity> findFirstByComponentAndResolvedAtIsNull(String component);

    List<ServiceIncidentEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
