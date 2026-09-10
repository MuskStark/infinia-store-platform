package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.BeeLevelService;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.EcosystemExportService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.app.upstream.UpstreamArtifactService;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.scanner.Ed25519Signer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FengYu-facing delivery surfaces (aggregation plan §7.1/§8): the signed
 * Native install manifest consumed by the host's SkillInstaller/McpInstaller,
 * the offline install package downloaded from the web store, plus the MCP and
 * Codex compatibility catalogs. Every surface points at the same
 * scanned/signed store releases — compatibility views are never a separate
 * trust root.
 */
@RestController
public class NativeInstallController {

    private static final long TICKET_TTL_SECONDS = 24 * 3600;
    private static final String MANIFEST_ENTRY = "install-manifest.json";
    private static final String CHECKSUMS_ENTRY = "checksums.txt";

    private final CatalogService catalog;
    private final ListingRepository listings;
    private final BeeLevelService beeLevels;
    private final TicketService tickets;
    private final StoreProperties properties;
    private final BlobStorage blobs;
    private final UpstreamArtifactService upstreamArtifacts;
    private final ObjectMapper mapper = new ObjectMapper();

    public NativeInstallController(CatalogService catalog, ListingRepository listings,
            BeeLevelService beeLevels, TicketService tickets, StoreProperties properties,
            BlobStorage blobs, UpstreamArtifactService upstreamArtifacts) {
        this.catalog = catalog;
        this.listings = listings;
        this.beeLevels = beeLevels;
        this.tickets = tickets;
        this.properties = properties;
        this.blobs = blobs;
        this.upstreamArtifacts = upstreamArtifacts;
    }

    /**
     * GET /api/v1/releases/{id}/install-manifest?client=fengyu — the Native
     * install contract (plan §7.1): coordinate, type, signed artifact, install
     * mode and default-enabled state, resolved per release.
     */
    @GetMapping("/api/v1/releases/{releaseId}/install-manifest")
    public ResponseEntity<Map<String, Object>> installManifest(@PathVariable java.util.UUID releaseId,
            @RequestParam(defaultValue = "fengyu") String client) {
        Release release = catalog.releaseOrThrow(releaseId);
        if (!release.installable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "release_not_installable", "status", release.status.name()));
        }
        Listing listing = listings.findById(release.listingId).orElse(null);
        if (listing == null) {
            return ResponseEntity.notFound().build();
        }
        // The manifest embeds a long-lived download ticket — Infinia Level gate first.
        beeLevels.requireListingAccess(listing);
        Release.ArtifactInfo artifact = packageArtifact(release);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(installDocument(listing, release, artifact, client, null));
    }

    /**
     * GET /api/v1/releases/{id}/install-package — the web-downloadable offline
     * install package: the Native install manifest plus its signed artifact and
     * a sha256sum checksums file in one ZIP. The host's local install mode
     * imports the file, verifies the artifact against the embedded sha256 /
     * Ed25519 signature and runs the type-specific installer — same manifest
     * contract as {@code install-manifest}, same trust root, no store connection
     * required (plan §5.2 RAW_ARTIFACT provenance rule).
     */
    @GetMapping("/api/v1/releases/{releaseId}/install-package")
    public ResponseEntity<StreamingResponseBody> installPackage(@PathVariable java.util.UUID releaseId,
            @RequestParam(defaultValue = "fengyu") String client) {
        Release release = catalog.releaseOrThrow(releaseId);
        if (!release.installable()) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Release is not installable (status " + release.status + ")");
        }
        Listing listing = listings.findById(release.listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Listing not found for release " + releaseId));
        beeLevels.requireListingAccess(listing);
        Release.ArtifactInfo artifact = packageArtifact(release);
        if (artifact == null) {
            throw new DomainException(StoreErrorCode.NOT_FOUND,
                    "Release has no installable PACKAGE artifact");
        }
        if (artifact.blobKey() != null && artifact.blobKey().startsWith("upstream/")) {
            // Legacy pre-materialization row: store + platform-sign the payload so
            // the package never ships digest-less bytes (same rule as tickets).
            artifact = materialize(release, artifact);
        }
        final Release.ArtifactInfo packaged = artifact;
        if (packaged.blobKey() == null || packaged.sha256() == null) {
            // Fail closed BEFORE headers are sent — an NPE inside the streaming
            // body would hand the browser a truncated 200 zip instead of a problem.
            throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                    "PACKAGE artifact has no stored blob/digest; refusing to package");
        }
        String artifactEntry = "artifact/" + zipSafeName(packaged.filename());
        Map<String, Object> manifest = installDocument(listing, release, packaged, client,
                artifactEntry);
        byte[] manifestBytes = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(manifest);
        String checksums = packaged.sha256() + "  " + artifactEntry + "\n"
                + Ed25519Signer.sha256Hex(manifestBytes) + "  " + MANIFEST_ENTRY + "\n";
        String filename = listing.namespace + "." + listing.slug + "-" + release.version
                + "-install-package.zip";
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(manifestBytes);
                zip.closeEntry();
                // Streamed from the blob port — the artifact never buffers in
                // memory, mirroring ticketed downloads.
                zip.putNextEntry(new ZipEntry(artifactEntry));
                try (InputStream in = blobs.open(packaged.blobKey())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(CHECKSUMS_ENTRY));
                zip.write(checksums.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            // Only a fully streamed package counts as a listing download (audit 3.1).
            listings.incrementDownloads(listing.id);
        };
        return ResponseEntity.ok()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .body(body);
    }

    /** Upstream virtual rows must become signed blobs before they enter a package. */
    private Release.ArtifactInfo materialize(Release release, Release.ArtifactInfo artifact) {
        try {
            return upstreamArtifacts.materializeVirtualArtifact(release, artifact);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("changed since sync")) {
                throw new DomainException(StoreErrorCode.UPSTREAM_DRIFTED, message);
            }
            if (e instanceof UpstreamArtifactService.UpstreamPayloadRejectedException) {
                throw new DomainException(StoreErrorCode.SCAN_FAILED, message);
            }
            throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                    "Upstream materialization failed: " + message);
        } catch (IOException | InterruptedException e) {
            throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                    "Upstream fetch failed: " + e.getMessage());
        }
    }

    /** Zip entry names must stay inside the package — no path separators. */
    private static String zipSafeName(String filename) {
        String name = filename == null ? "artifact.bin" : filename;
        String safe = name.replace('/', '_').replace('\\', '_').trim();
        return safe.isBlank() ? "artifact.bin" : safe;
    }

    /**
     * The shared Native install document (plan §7.1). {@code packagedArtifactEntry}
     * is set for offline packages and adds the in-package artifact filename plus
     * the package layout block; everything else is byte-identical to the online
     * manifest so the host runs one parser for both.
     */
    private Map<String, Object> installDocument(Listing listing, Release release,
            Release.ArtifactInfo artifact, String client, String packagedArtifactEntry) {
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        String signature = tickets.sign("download", artifact.blobKey(), expiresAt);
        String url = properties.baseUrl() + "/api/v1/blobs/" + artifact.blobKey() + "?"
                + TicketService.encodeTicketParams("download", artifact.blobKey(), expiresAt,
                        signature);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("coordinate", listing.coordinate()
                .withVersion(release.version).toString());
        manifest.put("type", listing.type.name());
        manifest.put("sourceReleaseId", release.id.toString());
        boolean live = artifact.blobKey() != null
                && artifact.blobKey().startsWith("upstream/");
        Map<String, Object> artifactView = new LinkedHashMap<>();
        artifactView.put("url", url);
        artifactView.put("delivery", live ? "LIVE_UPSTREAM" : "IMMUTABLE_BLOB");
        if (!live) {
            artifactView.put("sha256", artifact.sha256());
            artifactView.put("signature", artifact.signature() == null ? "" : artifact.signature());
            artifactView.put("keyId", artifact.keyId() == null ? "" : artifact.keyId());
            artifactView.put("size", artifact.size());
        }
        if (packagedArtifactEntry != null) {
            artifactView.put("filename", zipSafeName(artifact.filename()));
        }
        manifest.put("artifact", artifactView);
        manifest.put("dependencies", release.dependencies.stream()
                .map(d -> Map.of("coordinate", d.coordinate(), "range", d.range(),
                        "optional", d.optional())).toList());
        manifest.put("permissions", release.permissions.stream()
                .map(p -> Map.of("permissionId", p.permissionId(), "scope", p.scope(),
                        "required", p.required())).toList());

        Map<String, Object> install = new LinkedHashMap<>();
        switch (listing.type) {
            case SKILL -> {
                install.put("mode", "SKILL_DIRECTORY");
                install.put("defaultEnabled", true);
            }
            case MCP -> {
                install.put("mode", "MCP_TEMPLATE");
                install.put("defaultEnabled", false); // plan §6.2: never enabled on install
                install.put("secretsPolicy", "LOCAL_ONLY");
            }
            case PLUGIN -> {
                install.put("mode", "PLUGIN_PACKAGE");
                install.put("defaultEnabled", true);
            }
            default -> install.put("mode", listing.type.name());
        }
        manifest.put("install", install);
        if (packagedArtifactEntry != null) {
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("manifest", MANIFEST_ENTRY);
            files.put("artifact", packagedArtifactEntry);
            files.put("checksums", CHECKSUMS_ENTRY);
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("format", "infinia-install-package");
            pkg.put("schemaVersion", 1);
            pkg.put("files", files);
            manifest.put("package", pkg);
        }
        manifest.put("client", client);
        manifest.put("resolvedAt", Instant.now().toString());
        return manifest;
    }

    /**
     * GET /api/v1/compat/fengyu/mcp-catalog — MCP entries in the legacy
     * catalog shape, plus the deployment summary the host needs to decide
     * install handling (remote template vs stdio package).
     */
    @GetMapping("/api/v1/compat/fengyu/mcp-catalog")
    public List<Map<String, Object>> mcpCatalog() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Release release : catalog.latestVisibleByType(
                dev.infinia.store.contract.type.ListingType.MCP)) {
            Listing listing = listings.findById(release.listingId).orElse(null);
            if (listing == null || !listing.isPubliclyVisible()
                    || (listing.minBeeLevel > 0
                    && listing.minBeeLevel > beeLevels.viewerLevel())) {
                continue;
            }
            Release.ArtifactInfo artifact = packageArtifact(release);
            if (artifact == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", listing.namespace + "." + listing.slug);
            entry.put("name", listing.name("en"));
            entry.put("description", listing.summary("en"));
            entry.put("version", release.version.toString());
            entry.put("author", listing.namespace);
            entry.put("icon", listing.iconUrl);
            entry.put("homepage", null);
            entry.put("downloadUrl", directDownloadUrl(artifact));
            entry.put("official", false);
            if (artifact.blobKey() == null || !artifact.blobKey().startsWith("upstream/")) {
                entry.put("sha256", artifact.sha256());
            }
            entry.put("transport", "STREAMABLE_HTTP");
            entries.add(entry);
        }
        return entries;
    }

    /**
     * GET /api/v1/compat/fengyu/codex/catalog — fixed Git sources plus digests
     * so Codex-side tooling can pin what it clones (plan §8).
     */
    @GetMapping("/api/v1/compat/fengyu/codex/catalog")
    public Map<String, Object> codexCatalog() {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Release release : catalog.latestVisibleByType(
                dev.infinia.store.contract.type.ListingType.SKILL)) {
            Listing listing = listings.findById(release.listingId).orElse(null);
            if (listing == null || !listing.isPubliclyVisible()
                    || (listing.minBeeLevel > 0
                    && listing.minBeeLevel > beeLevels.viewerLevel())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", listing.namespace + "-" + listing.slug);
            entry.put("skillId", listing.namespace + "." + listing.slug);
            entry.put("version", release.version.toString());
            entry.put("listing", "/api/v1/listings/" + listing.namespace + "/" + listing.slug);
            entry.put("installManifest", "/api/v1/releases/" + release.id
                    + "/install-manifest?client=fengyu");
            skills.add(entry);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("generator", "infinia-store");
        document.put("skills", skills);
        return document;
    }

    private String directDownloadUrl(Release.ArtifactInfo artifact) {
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        String signature = tickets.sign("download", artifact.blobKey(), expiresAt);
        return properties.baseUrl() + "/api/v1/blobs/" + artifact.blobKey() + "?"
                + TicketService.encodeTicketParams("download", artifact.blobKey(), expiresAt,
                        signature);
    }

    /**
     * The installable PACKAGE artifact — the same deterministic selection
     * (UNIVERSAL first, then filename) every export surface uses, so manifest,
     * offline package and catalogs can never disagree on multi-artifact releases.
     */
    private static Release.ArtifactInfo packageArtifact(Release release) {
        return EcosystemExportService.packageArtifact(release);
    }
}
