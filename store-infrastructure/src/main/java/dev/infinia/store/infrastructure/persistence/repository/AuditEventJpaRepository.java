package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, java.util.UUID> {

    List<AuditEventEntity> findTop200ByOrderByOccurredAtDesc();

    List<AuditEventEntity> findTop200ByResourceTypeOrderByOccurredAtDesc(String resourceType);
}
