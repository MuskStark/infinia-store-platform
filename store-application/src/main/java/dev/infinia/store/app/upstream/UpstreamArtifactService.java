package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.UpstreamRepositories;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Pass-through artifact delivery (aggregation plan §5.2/§7): upstream content is
 * never persisted as a store blob — at download time the provenance row is
 * replayed (discover → normalize → build) and the rebuilt package is verified
 * against the recorded content digest before streaming. Upstream drift since
 * the last sync fails the download instead of serving unverifiable bytes.
 */
@Service
public class UpstreamArtifactService {

    private final UpstreamRepositories.UpstreamItemRepository items;
    private final UpstreamRepositories.UpstreamReleaseRepository releases;
    private final PublishingRepositories.UpstreamSourceRepository sources;
    private final RepoFetcher fetcher;
    private final List<UpstreamAdapter> adapters;
    private final UpstreamPackageBuilder builder;

    public UpstreamArtifactService(UpstreamRepositories.UpstreamItemRepository items,
            UpstreamRepositories.UpstreamReleaseRepository releases,
            PublishingRepositories.UpstreamSourceRepository sources,
            RepoFetcher fetcher, List<UpstreamAdapter> adapters,
            UpstreamPackageBuilder builder) {
        this.items = items;
        this.releases = releases;
        this.sources = sources;
        this.fetcher = fetcher;
        this.adapters = adapters;
        this.builder = builder;
    }

    /** itemId is the UUID encoded in the virtual blobKey (upstream/<uuid>). */
    public byte[] rebuild(UUID itemId, String releaseVersion) throws IOException,
            InterruptedException {
        UpstreamItem item = items.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown upstream artifact: " + itemId));
        UpstreamSource source = sources.findById(item.sourceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Upstream source missing: " + item.sourceId()));

        UpstreamAdapter adapter = resolve(source);
        NormalizedItem normalized = adapter.discover(source, fetcher).stream()
                .filter(n -> item.externalId().equals(n.externalId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Upstream no longer exposes " + item.externalId()));

        String digest = builder.contentDigest(normalized.skillFiles(),
                normalized.mcpTemplate());
        if (!digest.equalsIgnoreCase(item.contentSha256())) {
            throw new UpstreamDriftedException(item.externalId(), digest,
                    item.contentSha256());
        }
        return "MCP".equals(normalized.kind()) ? normalized.mcpTemplate()
                : builder.buildSkillPackage(source.targetNamespace(), normalized.slug(),
                        normalized.name(), normalized.description(),
                        normalized.skillFiles(),
                        releaseVersion == null ? baseVersion(normalized) : releaseVersion);
    }

    private UpstreamAdapter resolve(UpstreamSource source) {
        String requested = source.adapterType() == null || source.adapterType().isBlank()
                ? UpstreamAdapter.AUTO : source.adapterType().trim().toUpperCase();
        return adapters.stream()
                .filter(a -> a.type().equals(requested)
                        || (UpstreamAdapter.AUTO.equals(requested)
                                && a.type().equals(UpstreamAdapter.CLAUDE_MARKETPLACE)))
                .findFirst()
                .orElse(adapters.get(0));
    }

    private static String baseVersion(NormalizedItem item) {
        String v = item.version();
        return v != null && dev.infinia.store.contract.semver.SemVer.isValid(v) ? v : "0.0.0";
    }

    /** Upstream changed since the sync that recorded this artifact. */
    public static final class UpstreamDriftedException extends RuntimeException {
        public UpstreamDriftedException(String externalId, String actual, String expected) {
            super("Upstream content for " + externalId + " changed since sync (expected "
                    + expected + ", got " + actual + ") — re-sync the upstream");
        }
    }
}
