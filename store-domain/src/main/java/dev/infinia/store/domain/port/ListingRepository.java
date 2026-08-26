package dev.infinia.store.domain.port;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Listing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for catalog listings. */
public interface ListingRepository {

    Optional<Listing> findById(UUID id);

    Optional<Listing> findByCoordinate(InfiniaCoordinate coordinate);

    List<Listing> findByIds(List<UUID> ids);

    boolean existsByNamespaceAndSlugAndType(String namespace, String slug, ListingType type);

    ListingQuery.ListingPage search(ListingQuery query);

    List<Listing> findByPublisher(UUID publisherUserId);

    void save(Listing listing);

    void incrementDownloads(UUID listingId);

    void incrementFavorites(UUID listingId, long delta);
}
