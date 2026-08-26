package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.port.LibraryRepositories;
import dev.infinia.store.infrastructure.persistence.entity.FavoriteEntity;
import dev.infinia.store.infrastructure.persistence.repository.FavoriteJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class FavoriteAdapter implements LibraryRepositories.FavoriteRepository {

    private final FavoriteJpaRepository jpa;

    public FavoriteAdapter(FavoriteJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<LibraryRepositories.FavoriteMark> findByUserId(UUID userId) {
        return jpa.findByUserIdOrderByAddedAtDesc(userId).stream()
                .map(f -> new LibraryRepositories.FavoriteMark(f.listingId, f.addedAt))
                .toList();
    }

    @Override
    public boolean exists(UUID userId, UUID listingId) {
        return jpa.existsByUserIdAndListingId(userId, listingId);
    }

    @Override
    @Transactional
    public void add(UUID userId, UUID listingId, Instant addedAt) {
        FavoriteEntity e = new FavoriteEntity();
        e.id = UUID.randomUUID();
        e.userId = userId;
        e.listingId = listingId;
        e.addedAt = addedAt;
        jpa.save(e);
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID listingId) {
        jpa.deleteByUserIdAndListingId(userId, listingId);
    }

    @Override
    public long countByListing(UUID listingId) {
        return jpa.countByListingId(listingId);
    }
}
