package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.PublisherService;
import dev.infinia.store.app.service.ReviewService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.model.Organization;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.UploadSessionInfo;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Intranet admin manual upload of host-app update packages (the store replaces
 * the FY-Proxy distribution center, design §8.4): ensures the conventional APP
 * listing ({@code store.app-coordinate}), drafts the release, hands out the
 * same presigned upload URL the publisher pipeline uses, and publishes
 * immediately on command — the platform admin IS the review decision. The
 * compat mirror ({@code /api/v1/compat/fengyu/fengyu-releases/...}) and the
 * app update feed pick the release up as soon as it is PUBLISHED.
 */
@RestController
@RequestMapping("/api/v1/admin/app-releases")
public class AdminAppReleaseController {

    /**
     * {@code version} and {@code channel} are optional — both are inferred from
     * the package filename when omitted ({@code Infinia-<semver>-...}; a
     * pre-release suffix names the channel, otherwise stable).
     */
    record StartUploadRequest(String version, String channel, String changelog,
            String filename, Long size) {}

    record StartUploadResponse(String listingId, String releaseId, String version,
            String channel, String uploadUrl, String method, String kind, String platform,
            String arch, String variant, String expiresAt) {}

    record ArtifactSummary(String filename, String kind, String platform, String arch,
            String variant, long size, String sha256) {}

    record AppReleaseSummary(String releaseId, String version, String channel, String status,
            String publishedAt, List<ArtifactSummary> artifacts) {}

    private final PublisherService publisher;
    private final ReviewService reviewService;
    private final CatalogService catalog;
    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final IdentityRepositories.OrganizationRepository organizations;
    private final TicketService tickets;
    private final StoreProperties properties;
    private final CurrentPrincipal principal;

    public AdminAppReleaseController(PublisherService publisher, ReviewService reviewService,
            CatalogService catalog, ListingRepository listings, ReleaseRepository releases,
            IdentityRepositories.NamespaceRepository namespaces,
            IdentityRepositories.OrganizationRepository organizations, TicketService tickets,
            StoreProperties properties, CurrentPrincipal principal) {
        this.publisher = publisher;
        this.reviewService = reviewService;
        this.catalog = catalog;
        this.listings = listings;
        this.releases = releases;
        this.namespaces = namespaces;
        this.organizations = organizations;
        this.tickets = tickets;
        this.properties = properties;
        this.principal = principal;
    }

    /** The conventional host listing's releases, newest first (drafts included). */
    @GetMapping
    public List<AppReleaseSummary> list() {
        Listing listing = appListing();
        if (listing == null) {
            return List.of();
        }
        return releases.findByListingId(listing.id).stream()
                .sorted(Comparator.comparing((Release r) -> r.createdAt).reversed())
                .map(AdminAppReleaseController::toSummary)
                .toList();
    }

    /**
     * Start a manual upload: ensures the listing exists, drafts the release for
     * {@code version}, and returns the presigned PUT URL for the package bytes
     * (same ticketed pipeline as the publisher portal).
     */
    @PostMapping
    public ResponseEntity<StartUploadResponse> start(@RequestBody StartUploadRequest request) {
        UUID adminId = requireAdmin();
        if (request.filename() == null || request.filename().isBlank()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "filename is required");
        }
        String filename = request.filename().trim();
        String version = request.version() == null || request.version().isBlank()
                ? inferVersion(filename)
                : request.version().trim();
        String channel = request.channel() == null || request.channel().isBlank()
                ? inferChannel(version) : request.channel().trim();
        Listing listing = ensureAppListing(adminId);
        Release release = publisher.createDraftRelease(adminId, true, listing,
                new PublisherDtos.CreateReleaseRequest(version, channel,
                        null, "GPL-3.0", null,
                        request.changelog() == null || request.changelog().isBlank()
                                ? "Infinia host release " + version
                                : request.changelog(),
                        null, null, null));
        UploadSessionInfo session = publisher.createUploadSession(adminId, true, release,
                filename, null, null, null, null,
                request.size() == null ? 0 : request.size());
        Instant expiresAt = session.expiresAt;
        String signature = tickets.sign("upload", session.id.toString(), expiresAt);
        String uploadUrl = "/api/v1/blobs/uploads/" + session.id + "?"
                + TicketService.encodeTicketParams("upload", session.id.toString(), expiresAt,
                        signature);
        return ResponseEntity.status(HttpStatus.CREATED).body(new StartUploadResponse(
                listing.id.toString(), release.id.toString(), version,
                release.channel.name().toLowerCase(), uploadUrl, "PUT",
                session.kind.name(), session.platform.name().toLowerCase(),
                session.arch.name().toLowerCase(), session.variant, expiresAt.toString()));
    }

    /**
     * Publish a manually uploaded release immediately (signing the envelope and
     * every artifact like an approval). Only freshly uploaded releases qualify.
     */
    @PostMapping("/{releaseId}/publish")
    public AppReleaseSummary publish(@PathVariable UUID releaseId) {
        Release release = catalog.releaseOrThrow(releaseId);
        return toSummary(reviewService.publishAdminUpload(requireAdmin(), release));
    }

    /**
     * Delete a manually uploaded release (any status — removal supersedes a
     * yank). The update feed and compat mirror stop serving it immediately.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/{releaseId}")
    public ResponseEntity<Void> delete(@PathVariable UUID releaseId,
            @RequestBody(required = false) DeleteReasonBody body) {
        reviewService.deleteRelease(requireAdmin(), releaseId,
                body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    record DeleteReasonBody(String reason) {}

    // ---- helpers ----

    /** The conventional host listing, or null when nothing was uploaded yet. */
    private Listing appListing() {
        InfiniaCoordinate coordinate = InfiniaCoordinate.parse(properties.appCoordinate());
        return listings.findByCoordinate(coordinate).orElse(null);
    }

    /**
     * Finds the conventional host listing, creating it (and reserving its
     * namespace) on first use — the intranet first-run path. The listing is
     * owned by the admin, but the PLATFORM_ADMIN bypass lets later uploads work
     * no matter who owns it (the CI account, for example).
     */
    private Listing ensureAppListing(UUID adminId) {
        Listing existing = appListing();
        if (existing != null) {
            return existing;
        }
        InfiniaCoordinate coordinate = InfiniaCoordinate.parse(properties.appCoordinate());
        if (coordinate.type != dev.infinia.store.contract.type.ListingType.APP) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "store.app-coordinate must point at an APP listing: " + coordinate);
        }
        if (namespaces.findByName(coordinate.namespace).isEmpty()) {
            // Creating an organization reserves the matching namespace (design §7.1)
            // — same as the OrganizationController flow, executed as the admin.
            Instant now = Instant.now();
            UUID orgId = UuidV7.generate();
            organizations.save(new Organization(orgId, coordinate.namespace,
                    "Infinia Official", adminId, now));
            organizations.addMember(new Organization.Member(orgId, adminId,
                    dev.infinia.store.contract.type.UserRole.ORG_ADMIN, now));
            namespaces.save(new Namespace(UuidV7.generate(), coordinate.namespace, null,
                    orgId, false, now));
        }
        return publisher.createListing(adminId, true, new PublisherDtos.CreateListingRequest(
                coordinate.namespace, coordinate.slug, "APP", "Productivity",
                List.of("host", "official"), null, "Infinia Host",
                "The local-first FengYu host application.", null, null, null));
    }

    /**
     * Pulls the version out of a conventional host package filename —
     * {@code Infinia-5.0.0-win32-x64-portable.zip}, {@code Infinia-JRE-4.1.0-win-x64-setup.exe},
     * {@code Infinia-UOS-4.0.0-linux-x64.deb} — tolerating the variant prefixes.
     */
    static String inferVersion(String filename) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+(?:-(?:alpha|beta|rc|nightly)(?:\\.\\d+)*)?)")
                .matcher(filename);
        if (!m.find()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "cannot infer a version from filename '" + filename
                            + "' — rename it like Infinia-<version>-win32-x64-portable.zip"
                            + " or pass version explicitly");
        }
        return m.group(1);
    }

    /** A pre-release suffix names the channel ({@code -beta.2} → beta); else stable. */
    static String inferChannel(String version) {
        String lower = version.toLowerCase();
        int dash = lower.indexOf('-');
        if (dash < 0) {
            return "stable";
        }
        String label = lower.substring(dash + 1);
        if (label.startsWith("alpha")) {
            return "alpha";
        }
        if (label.startsWith("beta") || label.startsWith("rc") || label.startsWith("nightly")) {
            return label.startsWith("nightly") ? "nightly" : "beta";
        }
        return "stable";
    }

    /** Security chain gates /api/v1/admin/** to PLATFORM_ADMIN; assert it again. */
    private UUID requireAdmin() {
        var current = principal.require();
        if (!current.hasRole("PLATFORM_ADMIN")) {
            throw DomainException.forbidden("Platform admin role required");
        }
        return current.userId();
    }

    private static AppReleaseSummary toSummary(Release release) {
        return new AppReleaseSummary(release.id.toString(), release.version.toString(),
                release.channel.name().toLowerCase(), release.status.name(),
                release.publishedAt == null ? null : release.publishedAt.toString(),
                release.artifacts.stream()
                        .map(a -> new ArtifactSummary(a.filename(), a.kind().name().toLowerCase(),
                                a.platform().name().toLowerCase(), a.arch().name().toLowerCase(),
                                a.variant(), a.size(), a.sha256()))
                        .toList());
    }
}
