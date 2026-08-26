package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.Entitlement;
import dev.infinia.store.domain.port.LibraryRepositories;
import dev.infinia.store.infrastructure.persistence.entity.EntitlementEntity;
import dev.infinia.store.infrastructure.persistence.repository.EntitlementJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class EntitlementAdapter implements LibraryRepositories.EntitlementRepository {

    private final EntitlementJpaRepository jpa;

    public EntitlementAdapter(EntitlementJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Entitlement> findByUserId(UUID userId) {
        return jpa.findByUserId(userId).stream().map(EntitlementAdapter::toDomain).toList();
    }

    @Override
    public Optional<Entitlement> findByUserAndListing(UUID userId, UUID listingId) {
        return jpa.findByUserIdAndListingId(userId, listingId).map(EntitlementAdapter::toDomain);
    }

    @Override
    public void save(Entitlement entitlement) {
        EntitlementEntity existing = jpa.findByUserIdAndListingId(entitlement.userId(),
                entitlement.listingId()).orElse(null);
        EntitlementEntity e = existing == null ? new EntitlementEntity() : existing;
        e.id = existing == null ? entitlement.id() : existing.id;
        e.userId = entitlement.userId();
        e.listingId = entitlement.listingId();
        e.free = entitlement.free();
        e.acquiredAt = entitlement.acquiredAt();
        jpa.save(e);
    }

    private static Entitlement toDomain(EntitlementEntity e) {
        return new Entitlement(e.id, e.userId, e.listingId, e.free, e.acquiredAt);
    }
}
