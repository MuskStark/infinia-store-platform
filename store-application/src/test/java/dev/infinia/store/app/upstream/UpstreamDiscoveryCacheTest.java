package dev.infinia.store.app.upstream;

import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.UpstreamRepositories;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery caching on the legacy pass-through path (audit 3.6): every download
 * ticket for a pre-materialization {@code upstream/<uuid>} row used to re-fetch
 * the whole upstream catalog; a short-lived cache collapses bursts into one
 * discovery round per source.
 */
class UpstreamDiscoveryCacheTest {

    private static final class CountingAdapter implements UpstreamAdapter {
        final AtomicInteger discoveries = new AtomicInteger();

        @Override
        public String type() {
            return "CLAUDE_MARKETPLACE";
        }

        @Override
        public List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher) {
            discoveries.incrementAndGet();
            NormalizedItem item = new NormalizedItem("ext-1", "SKILL", "Name", "slug",
                    "Description", "1.0.0", "path", "https://example.com", null, null, null);
            return List.of(item);
        }
    }

    @Test
    void repeatedPrepareWithinTtlRunsOneDiscovery() throws Exception {
        CountingAdapter adapter = new CountingAdapter();
        UpstreamSource source = new UpstreamSource(UUID.randomUUID(), "test",
                "https://example.com/marketplace.json", "agg", true, Instant.now(), true,
                null, "AUTO");
        UpstreamItem item = new UpstreamItem(UUID.randomUUID(), source.id(), "ext-1", null,
                "https://example.com", "path", null, null, "1.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "CLAUDE_MARKETPLACE", Instant.now(), Instant.now(), null);

        UpstreamArtifactService service = new UpstreamArtifactService(
                itemRepo(item), null, sourceRepo(source),
                new RepoFetcher(new MockEnvironment().withProperty(
                        "store.upstream.allow-internal", "true")),
                List.of(adapter), new UpstreamPackageBuilder(), null, null, null, null);

        // The recorded digest deliberately mismatches, so prepare() fails at the
        // drift check AFTER discovery — the point is counting discovery rounds.
        assertThrows(UpstreamArtifactService.UpstreamDriftedException.class,
                () -> service.prepare(item.id(), "1.0.0"));
        assertThrows(UpstreamArtifactService.UpstreamDriftedException.class,
                () -> service.prepare(item.id(), "1.0.0"));
        assertThrows(UpstreamArtifactService.UpstreamDriftedException.class,
                () -> service.prepare(item.id(), "1.0.0"));

        assertEquals(1, adapter.discoveries.get(),
                "bursts of downloads must share one discovery round");
    }

    @Test
    void differentSourcesDiscoverIndependently() throws Exception {
        CountingAdapter adapter = new CountingAdapter();
        UpstreamSource source = new UpstreamSource(UUID.randomUUID(), "a",
                "https://a.example.com/marketplace.json", "agg", true, Instant.now(), true,
                null, "AUTO");
        UpstreamItem item = new UpstreamItem(UUID.randomUUID(), source.id(), "ext-1", null,
                null, "path", null, null, "1.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "CLAUDE_MARKETPLACE", Instant.now(), Instant.now(), null);

        UpstreamArtifactService service = new UpstreamArtifactService(
                itemRepo(item), null, sourceRepo(source),
                new RepoFetcher(new MockEnvironment().withProperty(
                        "store.upstream.allow-internal", "true")),
                List.of(adapter), new UpstreamPackageBuilder(), null, null, null, null);

        assertTrue(assertThrows(UpstreamArtifactService.UpstreamDriftedException.class,
                () -> service.prepare(item.id(), "1.0.0")).getMessage().contains("ext-1"));
        assertEquals(1, adapter.discoveries.get());
    }

    private static UpstreamRepositories.UpstreamItemRepository itemRepo(UpstreamItem item) {
        return new UpstreamRepositories.UpstreamItemRepository() {
            @Override
            public void save(UpstreamItem saved) {
            }

            @Override
            public Optional<UpstreamItem> findById(UUID id) {
                return item.id().equals(id) ? Optional.of(item) : Optional.empty();
            }

            @Override
            public Optional<UpstreamItem> findExact(UUID sourceId, String externalId,
                    String contentSha256) {
                return Optional.empty();
            }

            @Override
            public Optional<UpstreamItem> findLatest(UUID sourceId, String externalId) {
                return Optional.empty();
            }

            @Override
            public Optional<UpstreamItem> findLatestByListingId(UUID listingId) {
                return Optional.empty();
            }

            @Override
            public List<UpstreamItem> findBySource(UUID sourceId) {
                return List.of();
            }
        };
    }

    private static PublishingRepositories.UpstreamSourceRepository sourceRepo(
            UpstreamSource source) {
        return new PublishingRepositories.UpstreamSourceRepository() {
            @Override
            public void save(UpstreamSource saved) {
            }

            @Override
            public Optional<UpstreamSource> findById(UUID id) {
                return source.id().equals(id) ? Optional.of(source) : Optional.empty();
            }

            @Override
            public List<UpstreamSource> findAll() {
                return List.of(source);
            }

            @Override
            public Optional<UpstreamSource> findByName(String name) {
                return Optional.empty();
            }
        };
    }
}
