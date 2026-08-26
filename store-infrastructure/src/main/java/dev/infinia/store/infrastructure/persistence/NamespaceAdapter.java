package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.NamespaceEntity;
import dev.infinia.store.infrastructure.persistence.repository.NamespaceJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class NamespaceAdapter implements IdentityRepositories.NamespaceRepository {

    private final NamespaceJpaRepository jpa;

    public NamespaceAdapter(NamespaceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Namespace> findByName(String name) {
        return jpa.findByName(name).map(NamespaceAdapter::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return jpa.existsByName(name);
    }

    @Override
    public void save(Namespace namespace) {
        NamespaceEntity e = jpa.findById(namespace.id()).orElseGet(NamespaceEntity::new);
        e.id = namespace.id();
        e.name = namespace.name();
        e.ownerUserId = namespace.ownerUserId();
        e.organizationId = namespace.organizationId();
        e.verified = namespace.verified();
        e.createdAt = namespace.createdAt();
        jpa.save(e);
    }

    @Override
    public List<Namespace> findOwnedBy(UUID userId, UUID organizationId) {
        if (userId == null && organizationId == null) {
            return List.of();
        }
        List<UUID> orgIds = organizationId == null ? List.of() : List.of(organizationId);
        return jpa.findByOwnerUserIdOrOrganizationIdIn(userId, orgIds).stream()
                .map(NamespaceAdapter::toDomain)
                .toList();
    }

    private static Namespace toDomain(NamespaceEntity e) {
        return new Namespace(e.id, e.name, e.ownerUserId, e.organizationId, e.verified, e.createdAt);
    }
}
