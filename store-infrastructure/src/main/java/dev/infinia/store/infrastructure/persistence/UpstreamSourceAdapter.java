package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.UpstreamSourceEntity;
import dev.infinia.store.infrastructure.persistence.repository.UpstreamSourceJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class UpstreamSourceAdapter
        implements PublishingRepositories.UpstreamSourceRepository {

    private final UpstreamSourceJpaRepository jpa;

    public UpstreamSourceAdapter(UpstreamSourceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(UpstreamSource source) {
        UpstreamSourceEntity e = jpa.findById(source.id()).orElseGet(UpstreamSourceEntity::new);
        e.id = source.id();
        e.name = source.name();
        e.marketplaceUrl = source.marketplaceUrl();
        e.targetNamespace = source.targetNamespace();
        e.enabled = source.enabled();
        e.lastSyncAt = source.lastSyncAt();
        e.lastSyncOk = source.lastSyncOk();
        e.lastError = source.lastError();
        jpa.save(e);
    }

    @Override
    public Optional<UpstreamSource> findById(UUID id) {
        return jpa.findById(id).map(UpstreamSourceAdapter::toDomain);
    }

    @Override
    public List<UpstreamSource> findAll() {
        return jpa.findAllByOrderByNameAsc().stream().map(UpstreamSourceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<UpstreamSource> findByName(String name) {
        return jpa.findByName(name).map(UpstreamSourceAdapter::toDomain);
    }

    private static UpstreamSource toDomain(UpstreamSourceEntity e) {
        return new UpstreamSource(e.id, e.name, e.marketplaceUrl, e.targetNamespace, e.enabled,
                e.lastSyncAt, e.lastSyncOk, e.lastError);
    }
}
