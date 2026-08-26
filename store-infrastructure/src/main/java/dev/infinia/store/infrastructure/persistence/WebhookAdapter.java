package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.WebhookInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.WebhookEntity;
import dev.infinia.store.infrastructure.persistence.repository.WebhookJpaRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WebhookAdapter implements PublishingRepositories.WebhookRepository {

    private final WebhookJpaRepository jpa;

    public WebhookAdapter(WebhookJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<WebhookInfo> findActiveByEventAndOrganization(String eventType, UUID organizationId) {
        return jpa.findByOrganizationIdAndActiveTrue(organizationId).stream()
                .filter(w -> w.events.contains(eventType))
                .map(WebhookAdapter::toDomain)
                .toList();
    }

    @Override
    public List<WebhookInfo> findByOrganizationId(UUID organizationId) {
        return jpa.findByOrganizationId(organizationId).stream().map(WebhookAdapter::toDomain).toList();
    }

    @Override
    public Optional<WebhookInfo> findById(UUID id) {
        return jpa.findById(id).map(WebhookAdapter::toDomain);
    }

    @Override
    public void save(WebhookInfo webhook) {
        WebhookEntity e = jpa.findById(webhook.id()).orElseGet(WebhookEntity::new);
        e.id = webhook.id();
        e.organizationId = webhook.organizationId();
        e.url = webhook.url();
        e.secret = webhook.secret();
        e.events = new ArrayList<>(webhook.events());
        e.active = webhook.active();
        e.createdAt = webhook.createdAt();
        jpa.save(e);
    }

    private static WebhookInfo toDomain(WebhookEntity e) {
        return new WebhookInfo(e.id, e.organizationId, e.url, e.secret, new ArrayList<>(e.events),
                e.active, e.createdAt);
    }
}
