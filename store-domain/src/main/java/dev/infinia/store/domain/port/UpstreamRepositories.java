package dev.infinia.store.domain.port;

import dev.infinia.store.domain.model.SyncRun;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provenance ports for upstream aggregation (plan §4.1). */
public final class UpstreamRepositories {

    private UpstreamRepositories() {}

    public interface UpstreamItemRepository {
        void save(UpstreamItem item);

        Optional<UpstreamItem> findById(UUID id);

        /** Exact (source, externalId, contentSha256) hit — the idempotency key. */
        Optional<UpstreamItem> findExact(UUID sourceId, String externalId,
                String contentSha256);

        /** Latest provenance row for one entry regardless of content revision. */
        Optional<UpstreamItem> findLatest(UUID sourceId, String externalId);

        List<UpstreamItem> findBySource(UUID sourceId);
    }

    public interface UpstreamReleaseRepository {
        void save(UpstreamRelease release);

        List<UpstreamRelease> findByItemId(UUID upstreamItemId);
    }

    public interface SyncRunRepository {
        void save(SyncRun run);
    }
}
