package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.ListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FengYu-facing delivery surfaces (aggregation plan §7.1/§8): the signed
 * Native install manifest consumed by the host's SkillInstaller/McpInstaller,
 * plus the MCP and Codex compatibility catalogs. Every surface points at the
 * same scanned/signed store releases — compatibility views are never a
 * separate trust root.
 */
@RestController
public class NativeInstallController {

    private static final long TICKET_TTL_SECONDS = 24 * 3600;

    private final CatalogService catalog;
    private final ListingRepository listings;
    private final TicketService tickets;
    private final StoreProperties properties;

    public NativeInstallController(CatalogService catalog, ListingRepository listings,
            TicketService tickets, StoreProperties properties) {
        this.catalog = catalog;
        this.listings = listings;
        this.tickets = tickets;
        this.properties = properties;
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
        Release.ArtifactInfo artifact = release.artifacts.stream()
                .filter(a -> a.kind() == ArtifactKind.PACKAGE).findFirst()
                .orElse(release.artifacts.isEmpty() ? null : release.artifacts.get(0));
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
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
        manifest.put("artifact", Map.of(
                "url", url,
                "sha256", artifact.sha256(),
                "signature", artifact.signature() == null ? "" : artifact.signature(),
                "keyId", artifact.keyId() == null ? "" : artifact.keyId(),
                "size", artifact.size()));
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
        manifest.put("client", client);
        manifest.put("resolvedAt", Instant.now().toString());
        return ResponseEntity.ok(manifest);
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
            if (listing == null || !listing.isPubliclyVisible()) {
                continue;
            }
            Release.ArtifactInfo artifact = release.artifacts.stream()
                    .filter(a -> a.kind() == ArtifactKind.PACKAGE).findFirst()
                    .orElse(release.artifacts.isEmpty() ? null : release.artifacts.get(0));
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
            entry.put("sha256", artifact.sha256());
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
            if (listing == null || !listing.isPubliclyVisible()) {
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
}
