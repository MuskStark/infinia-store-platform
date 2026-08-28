package dev.infinia.store.domain.port;

import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for releases. */
public interface ReleaseRepository {

    Optional<Release> findById(UUID id);

    List<Release> findByListingId(UUID listingId);

    Optional<Release> findByListingIdAndVersion(UUID listingId, String version);

    boolean existsByListingIdAndStatus(UUID listingId, String version, Iterable<ReleaseStatus> statuses);

    /** Highest published (or deprecated) release of a listing in a channel. */
    Optional<Release> findLatestVisible(UUID listingId, Channel channel);

    /** All currently published releases of one listing across channels. */
    List<Release> findVisibleByListingId(UUID listingId);

    /** All published releases of a given type — used by the app update feed. */
    List<Release> findVisibleByType(ListingType type);

    /** Release owning an artifact by blob key — pass-through downloads need its version. */
    Optional<Release> findByArtifactBlobKey(String blobKey);

    void save(Release release);
}
