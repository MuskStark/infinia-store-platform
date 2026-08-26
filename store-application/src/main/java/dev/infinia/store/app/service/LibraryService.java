package dev.infinia.store.app.service;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Entitlement;
import dev.infinia.store.domain.model.InstallEventRecord;
import dev.infinia.store.domain.port.LibraryRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Favorites, free entitlements and optional install telemetry (design §7.4, ADR-009). */
@Service
public class LibraryService {

    private final LibraryRepositories.FavoriteRepository favorites;
    private final LibraryRepositories.EntitlementRepository entitlements;
    private final LibraryRepositories.InstallEventRepository installEvents;
    private final ListingRepository listings;

    public LibraryService(LibraryRepositories.FavoriteRepository favorites,
            LibraryRepositories.EntitlementRepository entitlements,
            LibraryRepositories.InstallEventRepository installEvents,
            ListingRepository listings) {
        this.favorites = favorites;
        this.entitlements = entitlements;
        this.installEvents = installEvents;
        this.listings = listings;
    }

    @Transactional
    public void addFavorite(UUID userId, UUID listingId) {
        listings.findById(listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        if (!favorites.exists(userId, listingId)) {
            favorites.add(userId, listingId, Instant.now());
            listings.incrementFavorites(listingId, 1);
        }
    }

    @Transactional
    public void removeFavorite(UUID userId, UUID listingId) {
        if (favorites.exists(userId, listingId)) {
            favorites.remove(userId, listingId);
            listings.incrementFavorites(listingId, -1);
        }
    }

    /** Free listings are entitled on first acquisition (design §3.1). */
    @Transactional
    public void acquireFreeEntitlement(UUID userId, UUID listingId) {
        listings.findById(listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        if (entitlements.findByUserAndListing(userId, listingId).isEmpty()) {
            entitlements.save(new Entitlement(UuidV7.generate(), userId, listingId, true,
                    Instant.now()));
        }
    }

    @Transactional
    public boolean reportInstallEvent(UUID userId, String idempotencyKey, String coordinate,
            String version, String type, String action, String outcome, String hostVersion,
            String os, String arch, String occurredAt) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "idempotencyKey is required (max 128 chars)");
        }
        if (installEvents.existsByIdempotencyKey(idempotencyKey)) {
            return false; // duplicate — already reported, stays idempotent
        }
        Instant occurred = Instant.now();
        if (occurredAt != null) {
            try {
                occurred = Instant.parse(occurredAt);
            } catch (DateTimeParseException ignored) {
                // server time is authoritative when the client clock is unreadable
            }
        }
        installEvents.save(new InstallEventRecord(UuidV7.generate(), idempotencyKey, userId, null,
                coordinate, version, type, action, outcome, hostVersion, os, arch, occurred,
                Instant.now()));
        return true;
    }

    public List<LibraryRepositories.FavoriteMark> favorites(UUID userId) {
        return favorites.findByUserId(userId);
    }

    public List<Entitlement> entitlements(UUID userId) {
        return entitlements.findByUserId(userId);
    }

    public List<InstallEventRecord> installHistory(UUID userId) {
        return installEvents.findRecentByUserId(userId, 100);
    }
}
