package dev.infinia.store.app.upstream;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.PlatformSigningService;
import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.port.UpstreamRepositories;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Upstream payload delivery. Sync materializes every imported item into the
 * durable content-addressed blob store (audit 3.1: download tickets must carry
 * a real, verifiable digest), so the normal ticketed blob path serves upstream
 * artifacts like any publisher upload. Two legacy paths remain: pass-through
 * downloads for {@code upstream/<uuid>} URLs minted before materialization, and
 * an on-demand upgrade that converts such a row into a stored, platform-signed
 * artifact when a download ticket is requested for it.
 */
@Service
public class UpstreamArtifactService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamArtifactService.class);
    private static final String TEMP_PREFIX = "infinia-upstream-";
    private static final long PROCESS_ID = ProcessHandle.current().pid();

    private final UpstreamRepositories.UpstreamItemRepository items;
    private final UpstreamRepositories.UpstreamReleaseRepository releases;
    private final PublishingRepositories.UpstreamSourceRepository sources;
    private final RepoFetcher fetcher;
    private final List<UpstreamAdapter> adapters;
    private final UpstreamPackageBuilder builder;
    private final BlobStorage blobs;
    private final ReleaseRepository releaseRows;
    private final PlatformSigningService signing;
    private final StoreProperties properties;

    public UpstreamArtifactService(UpstreamRepositories.UpstreamItemRepository items,
            UpstreamRepositories.UpstreamReleaseRepository releases,
            PublishingRepositories.UpstreamSourceRepository sources,
            RepoFetcher fetcher, List<UpstreamAdapter> adapters,
            UpstreamPackageBuilder builder, BlobStorage blobs,
            ReleaseRepository releaseRows, PlatformSigningService signing,
            StoreProperties properties) {
        this.items = items;
        this.releases = releases;
        this.sources = sources;
        this.fetcher = fetcher;
        this.adapters = adapters;
        this.builder = builder;
        this.blobs = blobs;
        this.releaseRows = releaseRows;
        this.signing = signing;
        this.properties = properties;
    }

    /** A payload persisted as a durable, content-addressed store blob. */
    public record StoredPayload(String blobKey, long size, String sha256) {}

    /** A request-owned file that is deleted together with its workspace on close. */
    public record PreparedArtifact(Path file, Path workspace, long size, String sha256)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteTree(workspace);
        }
    }

    /** itemId is the UUID encoded in the virtual blobKey (upstream/<uuid>). */
    public PreparedArtifact prepare(UUID itemId, String releaseVersion) throws IOException,
            InterruptedException {
        UpstreamItem item = items.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown upstream artifact: " + itemId));
        UpstreamSource source = sources.findById(item.sourceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Upstream source missing: " + item.sourceId()));

        UpstreamAdapter adapter = resolve(source, item);
        NormalizedItem discovered = discoverCached(source, adapter).stream()
                .filter(n -> item.externalId().equals(n.externalId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Upstream no longer exposes " + item.externalId()));

        String metadataDigest = builder.metadataDigest(discovered);
        if (!metadataDigest.equalsIgnoreCase(item.contentSha256())) {
            throw new UpstreamDriftedException(item.externalId(), metadataDigest,
                    item.contentSha256());
        }
        String effectiveVersion = releaseVersion == null
                ? baseVersion(discovered) : releaseVersion;
        return buildArtifact(source, discovered, adapter, effectiveVersion);
    }

    /**
     * Discovery with a short-lived per-source cache (audit 3.6): every download
     * of a legacy pass-through artifact re-fetched the whole upstream catalog —
     * one discovery round is shared for a brief window, so bursts of download
     * tickets collapse into one upstream request. The TTL bounds how long a
     * drifted upstream can go unnoticed by re-verification.
     */
    private List<NormalizedItem> discoverCached(UpstreamSource source, UpstreamAdapter adapter)
            throws IOException, InterruptedException {
        CachedDiscovery cached = discoveryCache.get(source.id());
        if (cached != null
                && System.currentTimeMillis() - cached.fetchedAt() < DISCOVERY_CACHE_TTL_MILLIS) {
            return cached.items();
        }
        List<NormalizedItem> discovered = List.copyOf(adapter.discover(source, fetcher));
        discoveryCache.put(source.id(),
                new CachedDiscovery(discovered, System.currentTimeMillis()));
        return discovered;
    }

    private static final long DISCOVERY_CACHE_TTL_MILLIS = 30_000;

    private record CachedDiscovery(List<NormalizedItem> items, long fetchedAt) {}

    private final java.util.Map<UUID, CachedDiscovery> discoveryCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sync-time materialization (audit 3.1): fetch, scan and compatibility-pack
     * the just-discovered item, then persist it as a content-addressed store
     * blob. Returns unsigned metadata — the regular review approval signs the
     * stored bytes, exactly like a publisher upload.
     */
    public StoredPayload storePayload(UpstreamSource source, NormalizedItem discovered,
            UpstreamAdapter adapter, String releaseVersion) throws IOException,
            InterruptedException {
        try (PreparedArtifact prepared = buildArtifact(source, discovered, adapter,
                releaseVersion == null ? baseVersion(discovered) : releaseVersion)) {
            String blobKey = blobs.put(Files.newInputStream(prepared.file()),
                    properties.maxUploadBytes(), prepared.sha256());
            return new StoredPayload(blobKey, prepared.size(), prepared.sha256());
        }
    }

    /**
     * On-demand upgrade for rows persisted by the pre-materialization sync
     * (virtual {@code upstream/<uuid>} blob keys): fetches, scans, stores and
     * platform-signs the artifact, rewrites the release row to the stored blob
     * and returns the upgraded artifact info. Drift/scan failures surface as
     * {@link UpstreamDriftedException} / {@link UpstreamPayloadRejectedException}
     * so callers can fail closed instead of serving unverifiable bytes.
     */
    public Release.ArtifactInfo materializeVirtualArtifact(Release release,
            Release.ArtifactInfo virtual) throws IOException, InterruptedException {
        UUID itemId;
        try {
            itemId = UUID.fromString(virtual.blobKey().substring("upstream/".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed upstream artifact key");
        }
        try (PreparedArtifact prepared = prepare(itemId, release.version.toString())) {
            String blobKey = blobs.put(Files.newInputStream(prepared.file()),
                    properties.maxUploadBytes(), prepared.sha256());
            Release.ArtifactInfo stored = new Release.ArtifactInfo(virtual.id(),
                    virtual.kind(), virtual.platform(), virtual.arch(), virtual.variant(),
                    virtual.filename(), prepared.size(), prepared.sha256(),
                    signing.sign(blobs.open(blobKey)), signing.currentKeyId(), blobKey,
                    virtual.mimeType());
            release.artifacts = new ArrayList<>(release.artifacts.stream()
                    .map(a -> a.id() != null && a.id().equals(stored.id()) ? stored : a)
                    .toList());
            releaseRows.save(release);
            return stored;
        }
    }

    /** Workspace-scoped fetch → scan → compatibility-pack of one payload. */
    private PreparedArtifact buildArtifact(UpstreamSource source, NormalizedItem discovered,
            UpstreamAdapter adapter, String effectiveVersion) throws IOException,
            InterruptedException {
        Path workspace = Files.createTempDirectory(TEMP_PREFIX + PROCESS_ID + "-");
        try {
            UpstreamAdapter.MaterializedPayload payload = adapter.materializeToDirectory(
                    source, discovered, fetcher, workspace);
            NormalizedItem materialized = payload.metadata();
            Path artifact;
            if ("MCP".equals(materialized.kind())) {
                artifact = payload.mcpTemplate();
                if (artifact == null || !Files.isRegularFile(artifact)) {
                    throw new IOException("MCP upstream did not produce a template");
                }
            } else {
                if (payload.skillDirectory() == null) {
                    throw new IOException("Skill upstream did not produce a directory");
                }
                artifact = builder.buildSkillPackage(source.targetNamespace(),
                        materialized.slug(), materialized.name(), materialized.description(),
                        payload.skillDirectory(), effectiveVersion,
                        workspace.resolve(materialized.slug() + ".fys"));
            }
            var scan = new dev.infinia.store.scanner.PackageScanner()
                    .scan(materialized.kind(), effectiveVersion, artifact);
            if (scan.hasBlockingFindings()) {
                throw new UpstreamPayloadRejectedException(discovered.externalId(),
                        scan.findings.stream()
                                .map(dev.infinia.store.scanner.ScanResult.Finding::rule)
                                .distinct().toList());
            }
            return new PreparedArtifact(artifact, workspace, Files.size(artifact),
                    sha256(artifact));
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                deleteTree(workspace);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Replays the adapter the SYNC resolved for this item (audit P1-7). AUTO is a
     * sync-time probe: re-probing at download could disagree with the recorded
     * provenance (an MCP registry source resolving as CLAUDE_MARKETPLACE made the
     * externalId unfindable → 500). Rows persisted before the adapter type was
     * recorded (null) fall back to the legacy probe for compatibility.
     */
    UpstreamAdapter resolve(UpstreamSource source, UpstreamItem item) {
        if (item.adapterType() != null && !item.adapterType().isBlank()) {
            String recorded = item.adapterType().trim().toUpperCase();
            return adapters.stream()
                    .filter(a -> a.type().equals(recorded))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown adapter type " + recorded + " recorded for upstream item "
                                    + item.id()));
        }
        return legacyResolve(source);
    }

    /** Pre-adapter-persistence fallback: probe the source shape again. */
    UpstreamAdapter legacyResolve(UpstreamSource source) {
        String requested = source.adapterType() == null || source.adapterType().isBlank()
                ? UpstreamAdapter.AUTO : source.adapterType().trim().toUpperCase();
        if (UpstreamAdapter.AUTO.equals(requested)
                && SkillHubAdapter.matches(source.marketplaceUrl())) {
            // AUTO sources keep their discovery-time shape at download time.
            return adapters.stream()
                    .filter(a -> a.type().equals(UpstreamAdapter.SKILLHUB_REGISTRY))
                    .findFirst().orElse(adapters.get(0));
        }
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

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Removes workspaces left by a terminated process without touching live peers. */
    @PostConstruct
    void deleteStaleWorkspaces() {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(tempRoot)) {
            for (Path path : entries.filter(Files::isDirectory)
                    .filter(p -> String.valueOf(p.getFileName()).startsWith(TEMP_PREFIX))
                    .toList()) {
                String name = String.valueOf(path.getFileName());
                int end = name.indexOf('-', TEMP_PREFIX.length());
                if (end < 0) {
                    continue;
                }
                try {
                    long ownerPid = Long.parseLong(name.substring(TEMP_PREFIX.length(), end));
                    if (ownerPid != PROCESS_ID
                            && ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive)
                                    .orElse(false)) {
                        continue;
                    }
                    deleteTree(path);
                } catch (NumberFormatException ignored) {
                    // Workspace from a version that did not encode an owner PID.
                    deleteTree(path);
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean stale upstream workspaces: {}", e.getMessage());
        }
    }

    /** Upstream changed since the sync that recorded this artifact. */
    public static final class UpstreamDriftedException extends RuntimeException {
        public UpstreamDriftedException(String externalId, String actual, String expected) {
            super("Upstream content for " + externalId + " changed since sync (expected "
                    + expected + ", got " + actual + ") — re-sync the upstream");
        }
    }

    /** Download-time scan rejected the current upstream payload. */
    public static final class UpstreamPayloadRejectedException extends RuntimeException {
        public UpstreamPayloadRejectedException(String externalId, List<String> rules) {
            super("Upstream payload for " + externalId + " was blocked by security scan "
                    + rules);
        }
    }
}
