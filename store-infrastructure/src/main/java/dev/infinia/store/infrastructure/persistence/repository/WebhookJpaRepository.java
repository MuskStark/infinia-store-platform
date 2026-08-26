package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.WebhookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookJpaRepository extends JpaRepository<WebhookEntity, UUID> {

    List<WebhookEntity> findByOrganizationIdAndActiveTrue(UUID organizationId);

    List<WebhookEntity> findByOrganizationId(UUID organizationId);
}
