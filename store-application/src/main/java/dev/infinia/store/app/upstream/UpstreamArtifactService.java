package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Request-scoped upstream delivery: catalog metadata is replayed, the selected
 * payload alone is fetched, scanned and compatibility-packed through a bounded
 * request workspace. The workspace is deleted after streaming; nothing enters
 * durable blob storage.
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
        Path workspace = Files.createTempDirectory(TEMP_PREFIX + PROCESS_ID + "-");
        try {
            UpstreamItem item = items.findById(itemId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown upstream artifact: " + itemId));
            UpstreamSource source = sources.findById(item.sourceId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Upstream source missing: " + item.sourceId()));

            UpstreamAdapter adapter = resolve(source);
            NormalizedItem discovered = adapter.discover(source, fetcher).stream()
                    .filter(n -> item.externalId().equals(n.externalId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Upstream no longer exposes " + item.externalId()));

            String metadataDigest = builder.metadataDigest(discovered);
            if (!metadataDigest.equalsIgnoreCase(item.contentSha256())) {
                throw new UpstreamDriftedException(item.externalId(), metadataDigest,
                        item.contentSha256());
            }
            UpstreamAdapter.MaterializedPayload payload = adapter.materializeToDirectory(
                    source, discovered, fetcher, workspace);
            NormalizedItem materialized = payload.metadata();
            String effectiveVersion = releaseVersion == null
                    ? baseVersion(materialized) : releaseVersion;
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
                throw new UpstreamPayloadRejectedException(item.externalId(),
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

    private UpstreamAdapter resolve(UpstreamSource source) {
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
