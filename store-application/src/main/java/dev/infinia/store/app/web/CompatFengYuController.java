package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Release.ArtifactInfo;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compatibility catalog for the FengYu host's built-in plugin store (design §2.1:
 * PluginStoreController / FENGYU marketplace source type).
 *
 * The host fetches this URL server-side, renders entries in its existing store UI
 * and downloads {@code downloadUrl} directly with sha256 verification — so the
 * host integrates the Infinia store with zero code changes: the user just adds
 * {@code <store-base>/api/v1/compat/fengyu/catalog} as a FENGYU-type source.
 *
 * Entry fields mirror the host's MarketplaceCatalogEntry contract verbatim.
 * Publisher Ed25519 signature fields stay null: signature verification on the host
 * requires a locally trusted publisher key, and sha256 is always supplied so
 * plain-HTTP dev URLs still pass integrity checks.
 */
@RestController
public class CompatFengYuController {

    /**
     * Direct-download tickets live longer than the API's short-lived ticket default
     * because the host caches catalogs (TTL ~10 min) and the user may click install
     * afterwards; every catalog refresh re-mints fresh URLs.
     */
    private static final long COMPAT_TICKET_TTL_SECONDS = 24 * 3600;

    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final CatalogService catalog;
    private final dev.infinia.store.app.service.EcosystemExportService ecosystem;
    private final TicketService tickets;
    private final StoreProperties properties;

    public CompatFengYuController(ReleaseRepository releases, ListingRepository listings,
            IdentityRepositories.NamespaceRepository namespaces, CatalogService catalog,
            dev.infinia.store.app.service.EcosystemExportService ecosystem,
            TicketService tickets, StoreProperties properties) {
        this.releases = releases;
        this.listings = listings;
        this.namespaces = namespaces;
        this.catalog = catalog;
        this.ecosystem = ecosystem;
        this.tickets = tickets;
        this.properties = properties;
    }

    /** FengYu MarketplaceCatalogEntry-compatible plugin catalog (anonymous). */
    @GetMapping("/api/v1/compat/fengyu/catalog")
    public List<FengYuCatalogEntryDto> catalog() {
        // Latest published PLUGIN release per listing (host update detection compares
        // this version against the installed manifest version).
        Map<UUID, Release> latestByListing = latestPublishedByListing(ListingType.PLUGIN);
        if (latestByListing.isEmpty()) {
            return List.of();
        }

        Map<UUID, Listing> listingById = new HashMap<>();
        for (Listing listing : listings.findByIds(new ArrayList<>(latestByListing.keySet()))) {
            listingById.put(listing.id, listing);
        }

        List<FengYuCatalogEntryDto> entries = new ArrayList<>();
        for (Map.Entry<UUID, Release> e : latestByListing.entrySet()) {
            Listing listing = listingById.get(e.getKey());
            if (listing == null || !listing.isPubliclyVisible()) {
                continue;
            }
            Release release = e.getValue();
            ArtifactInfo artifact = packageArtifact(release);
            if (artifact == null) {
                continue;
            }
            entries.add(new FengYuCatalogEntryDto(
                    listing.namespace + "." + listing.slug,
                    listing.name("en"),
                    listing.summary("en"),
                    release.version.toString(),
                    listing.namespace,
                    listing.iconUrl,
                    listing.category,
                    release.permissions.stream().map(p -> p.permissionId()).toList(),
                    null,
                    directDownloadUrl(artifact),
                    false,
                    artifact.sha256(),
                    null,
                    null));
        }
        entries.sort(Comparator.comparing(FengYuCatalogEntryDto::name, Comparator.nullsLast(
                Comparator.naturalOrder())));
        return entries;
    }

    /**
     * FengYu SkillCatalogEntry-compatible skill catalog (anonymous), consumed via the
     * host property {@code fengyu.skills.catalog-url}. Same shape as the plugin
     * catalog minus category/permissions/integrity fields — the skill install path
     * only reads id/name/description/version/author/icon/homepage/downloadUrl/official.
     * The official flag stays false: the host treats it as "shipped by the FengYu
     * team", which no store publisher qualifies for.
     */
    @GetMapping("/api/v1/compat/fengyu/skills-catalog")
    public List<FengYuSkillEntryDto> skillsCatalog() {
        Map<UUID, Release> latestByListing = latestPublishedByListing(ListingType.SKILL);
        if (latestByListing.isEmpty()) {
            return List.of();
        }
        Map<UUID, Listing> listingById = new HashMap<>();
        for (Listing listing : listings.findByIds(new ArrayList<>(latestByListing.keySet()))) {
            listingById.put(listing.id, listing);
        }
        List<FengYuSkillEntryDto> entries = new ArrayList<>();
        for (Map.Entry<UUID, Release> e : latestByListing.entrySet()) {
            Listing listing = listingById.get(e.getKey());
            if (listing == null || !listing.isPubliclyVisible()) {
                continue;
            }
            ArtifactInfo artifact = packageArtifact(e.getValue());
            if (artifact == null) {
                continue;
            }
            entries.add(new FengYuSkillEntryDto(
                    listing.namespace + "." + listing.slug,
                    listing.name("en"),
                    listing.summary("en"),
                    e.getValue().version.toString(),
                    listing.namespace,
                    null,
                    null,
                    directDownloadUrl(artifact),
                    false));
        }
        entries.sort(Comparator.comparing(FengYuSkillEntryDto::name, Comparator.nullsLast(
                Comparator.naturalOrder())));
        return entries;
    }

    /**
     * Windows portable update mirror (anonymous). The FengYu Electron portable
     * updater calls {@code {FENGYU_UPDATE_API_BASE}/fengyu-releases/api/releases/latest?channel=...}
     * when the user sets the updateApiBase setting — this serves a
     * GitHub-releases-compatible object with the mandatory sha256 digest.
     */
    @GetMapping("/api/v1/compat/fengyu/fengyu-releases/api/releases/latest")
    public ResponseEntity<?> portableRelease(
            @RequestParam(required = false) String channel) {
        // Latest STABLE-channel APP release; beta/nightly stay on their own rollout
        // paths (design §8.4). Filter before picking the maximum version, otherwise
        // a newer prerelease would shadow the stable one.
        Release best = null;
        for (Release release : releases.findVisibleByType(ListingType.APP)) {
            if (release.channel != dev.infinia.store.contract.type.Channel.STABLE) {
                continue;
            }
            if (best == null || release.version.compareTo(best.version) > 0) {
                best = release;
            }
        }
        if (best == null) {
            return ResponseEntity.ok(List.of()); // host treats [] as "no update"
        }
        // Prefer the windows/x64 installer artifact; fall back to any PACKAGE.
        final Release app = best;
        ArtifactInfo artifact = app.artifacts.stream()
                .filter(a -> a.platform() == dev.infinia.store.contract.type.Platform.WINDOWS)
                .findFirst()
                .orElseGet(() -> packageArtifact(app));
        if (artifact == null) {
            return ResponseEntity.ok(List.of());
        }
        String version = best.version.toString();
        // The updater matches this exact asset name pattern.
        String assetName = "Infinia-" + version + "-win32-x64-portable.zip";
        Map<String, Object> asset = new HashMap<>();
        asset.put("name", assetName);
        asset.put("browser_download_url", directDownloadUrl(artifact));
        asset.put("digest", "sha256:" + artifact.sha256());
        Map<String, Object> release = new HashMap<>();
        release.put("tag_name", "v" + version);
        release.put("name", "Infinia " + version);
        release.put("html_url", properties.baseUrl() + "/web");
        release.put("prerelease", best.version.isPrerelease());
        release.put("assets", List.of(asset));
        return ResponseEntity.ok(release);
    }

    /**
     * CLAUDE-ecosystem marketplace covering skills AND MCP templates (anonymous).
     * Register this URL as a CLAUDE-type source in the host: it git-clones each
     * entry, imports skill directories and disabled MCP server definitions, and
     * re-cloning on update picks up the new exported commit — one store source
     * for everything that is not a .fyp plugin.
     */
    @GetMapping("/api/v1/compat/fengyu/claude-marketplace.json")
    public Map<String, Object> claudeMarketplace() {
        return ecosystem.marketplace();
    }

    // ---- helpers ----

    private Map<UUID, Release> latestPublishedByListing(ListingType type) {
        Map<UUID, Release> latestByListing = new HashMap<>();
        for (Release release : releases.findVisibleByType(type)) {
            latestByListing.merge(release.listingId, release,
                    (a, b) -> a.version.compareTo(b.version) >= 0 ? a : b);
        }
        return latestByListing;
    }

    private ArtifactInfo packageArtifact(Release release) {
        return release.artifacts.stream()
                .filter(a -> a.kind() == dev.infinia.store.contract.type.ArtifactKind.PACKAGE)
                .findFirst()
                .orElse(release.artifacts.isEmpty() ? null : release.artifacts.get(0));
    }

    private String directDownloadUrl(ArtifactInfo artifact) {
        Instant expiresAt = Instant.now().plusSeconds(COMPAT_TICKET_TTL_SECONDS);
        String signature = tickets.sign("download", artifact.blobKey(), expiresAt);
        return properties.baseUrl() + "/api/v1/blobs/" + artifact.blobKey() + "?"
                + TicketService.encodeTicketParams("download", artifact.blobKey(), expiresAt,
                        signature);
    }

    /** Field names/shape mirror the host's MarketplaceCatalogEntry JSON contract. */
    public record FengYuCatalogEntryDto(
            String id,
            String name,
            String description,
            String version,
            String author,
            String icon,
            String category,
            List<String> permissions,
            String homepage,
            String downloadUrl,
            boolean official,
            String sha256,
            String signature,
            String keyId) {
    }

    /** Field names/shape mirror the host's SkillCatalogEntry JSON contract. */
    public record FengYuSkillEntryDto(
            String id,
            String name,
            String description,
            String version,
            String author,
            String icon,
            String homepage,
            String downloadUrl,
            boolean official) {
    }
}
