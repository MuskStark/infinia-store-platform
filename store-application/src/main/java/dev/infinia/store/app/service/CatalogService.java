package dev.infinia.store.app.service;

import tools.jackson.databind.ObjectMapper;
import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.contract.api.CatalogDtos.CatalogItemDto;
import dev.infinia.store.contract.api.CatalogDtos.CatalogPageDto;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.envelope.ArtifactRef;
import dev.infinia.store.contract.envelope.ReleaseEnvelope;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.ListingQuery;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.CompatibilityEvaluator;
import dev.infinia.store.domain.service.DependencySolver;
import dev.infinia.store.domain.service.RolloutBucketer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalog, resolution, delivery and update-feed logic (design §5.3, §8.4, §9.4).
 */
@Service
public class CatalogService {

    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final StoreProperties properties;
    private final RolloutBucketer bucketer;
    private final ObjectMapper mapper;
    private final TicketService tickets;
    private final BeeLevelService beeLevels;

    public CatalogService(ListingRepository listings, ReleaseRepository releases,
            StoreProperties properties, ObjectMapper mapper, TicketService tickets,
            BeeLevelService beeLevels) {
        this.listings = listings;
        this.releases = releases;
        this.properties = properties;
        this.bucketer = new RolloutBucketer(properties.rolloutSecret());
        this.mapper = mapper;
        this.tickets = tickets;
        this.beeLevels = beeLevels;
    }

    // ---- catalog ----

    public record BrowseQuery(ListingType type, String text, String category, Channel channel,
            String hostVersion, String os, String arch, ListingQuery.ListingSort sort,
            Boolean featured, String afterId, int limit) {

        public BrowseQuery(ListingType type, String text, String category, Channel channel,
                String hostVersion, String os, String arch, ListingQuery.ListingSort sort,
                String afterId, int limit) {
            this(type, text, category, channel, hostVersion, os, arch, sort, null, afterId,
                    limit);
        }
    }

    public CatalogPageDto browse(BrowseQuery query) {
        ListingQuery.ListingPage page = listings.search(new ListingQuery(
                query.type(), query.text(), query.category(), query.channel(), null,
                query.sort() == null ? ListingQuery.ListingSort.RELEVANCE : query.sort(),
                null, query.afterId(), query.featured(), beeLevels.viewerLevel(),
                query.limit()));

        List<CatalogItemDto> items = new ArrayList<>();
        for (Listing listing : page.items()) {
            Release latest = latestCompatible(listing, query);
            if (latest == null) {
                // Catalog rows must be installable: listings without a published
                // release (e.g. rejected upstream imports) stay invisible.
                continue;
            }
            items.add(toCatalogItem(listing, latest));
        }
        return new CatalogPageDto(items, page.hasMore() ? page.lastId() : null,
                page.totalEstimate());
    }

    /** Finds a listing across all five types for (namespace, slug). */
    public Listing findListing(String namespace, String slug) {
        for (dev.infinia.store.contract.type.ListingType type
                : dev.infinia.store.contract.type.ListingType.values()) {
            var found = listings.findByCoordinate(
                    InfiniaCoordinate.of(type, namespace, slug));
            if (found.isPresent()) {
                return found.get();
            }
        }
        return null;
    }

    public Listing listingOrThrow(InfiniaCoordinate coordinate) {
        return listings.findByCoordinate(coordinate).orElseThrow(
                () -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found: " + coordinate.listingPart()));
    }

    /** Latest published release per listing of one type (compat surfaces). */
    public List<Release> latestVisibleByType(dev.infinia.store.contract.type.ListingType type) {
        java.util.Map<java.util.UUID, Release> latest = new java.util.HashMap<>();
        for (Release release : releases.findVisibleByType(type)) {
            latest.merge(release.listingId, release,
                    (a, b) -> a.version.compareTo(b.version) >= 0 ? a : b);
        }
        return List.copyOf(latest.values());
    }

    public List<Release> visibleReleases(Listing listing, Channel channel) {
        return releases.findVisibleByListingId(listing.id).stream()
                .filter(r -> channel == null || r.channel == channel)
                .toList();
    }

    // ---- resolution ----

    public DependencySolver.Result resolve(InfiniaCoordinate root, String range,
            String hostVersion, String os, String arch, Channel channel,
            java.util.Map<String, String> installed) {
        DependencySolver solver = new DependencySolver(this::candidatesFor);
        return solver.resolve(root, range,
                new DependencySolver.ClientEnvironment(hostVersion, os, arch, channel, installed));
    }

    /** Catalog port feeding the dependency solver with installable releases. */
    public List<DependencySolver.Candidate> candidatesFor(InfiniaCoordinate coordinate) {
        Listing listing = listings.findByCoordinate(coordinate).orElse(null);
        if (listing == null || !listing.isPubliclyVisible()) {
            return List.of();
        }
        // Bee-level gate: below-threshold listings resolve as if they did not
        // exist, for viewers without access (the root listing gets an explicit
        // bee_level_required problem from the controller instead).
        if (listing.minBeeLevel > 0 && listing.minBeeLevel > beeLevels.viewerLevel()) {
            return List.of();
        }
        List<DependencySolver.Candidate> candidates = new ArrayList<>();
        for (Release release : releases.findVisibleByListingId(listing.id)) {
            if (release.status != ReleaseStatus.PUBLISHED) {
                continue;
            }
            candidates.add(new DependencySolver.Candidate(
                    listing.coordinate().withVersion(release.version),
                    release.id.toString(), release.version, release.channel, release.requiresHost,
                    release.artifacts, release.permissions, release.dependencies));
        }
        candidates.sort((a, b) -> b.version().compareTo(a.version()));
        return candidates;
    }

    // ---- download tickets ----

    public Release releaseOrThrow(java.util.UUID id) {
        return releases.findById(id).orElseThrow(
                () -> new DomainException(StoreErrorCode.RELEASE_NOT_FOUND,
                        "Release not found: " + id));
    }

    public Release.ArtifactInfo pickArtifact(Release release, String artifactId,
            String os, String arch) {
        if (artifactId != null && !artifactId.isBlank()) {
            return release.artifacts.stream()
                    .filter(a -> a.id() != null && artifactId.equals(a.id().toString()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                            "Artifact not found: " + artifactId));
        }
        return CompatibilityEvaluator.bestArtifact(release,
                CompatibilityEvaluator.parsePlatform(os),
                CompatibilityEvaluator.parseArch(arch))
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "No artifact matches platform " + os + "/" + arch));
    }

    // ---- app update feed (design §8.4) ----

    public UpdateFeed appUpdate(String current, Channel channel, String os, String arch,
            String mode, String variant, String installId) {
        SemVer currentVersion = SemVer.parse(current);
        ArtifactKind requestedMode = parseAppMode(mode);
        dev.infinia.store.contract.type.Platform requestedPlatform =
                CompatibilityEvaluator.parsePlatform(os);
        dev.infinia.store.contract.type.Arch requestedArch =
                CompatibilityEvaluator.parseArch(arch);
        InfiniaCoordinate configured = InfiniaCoordinate.parse(properties.appCoordinate());
        Listing appListing = listings.findByCoordinate(configured).orElse(null);
        if (appListing == null || appListing.type != ListingType.APP
                || !appListing.isPubliclyVisible()
                // A bee-level gated APP listing is treated as absent here: the
                // host updater has no user context and must serve an empty feed
                // rather than 403 half-way through an update check (蜜蜂等级).
                || (appListing.minBeeLevel > 0
                        && appListing.minBeeLevel > beeLevels.viewerLevel())) {
            return new UpdateFeed(null, null, null, 0, null, List.of());
        }
        Release best = null;
        for (Release release : releases.findVisibleByListingId(appListing.id)) {
            if (release.status != ReleaseStatus.PUBLISHED || release.channel != channel) {
                continue;
            }
            if (!CompatibilityEvaluator.hostCompatible(release, current)) {
                continue;
            }
            if (CompatibilityEvaluator.appArtifacts(release, requestedPlatform, requestedArch,
                    requestedMode, variant).isEmpty()) {
                continue;
            }
            if (!bucketer.included(installId, release.rolloutPercent)) {
                continue;
            }
            if (best == null || release.version.compareTo(best.version) > 0) {
                best = release;
            }
        }
        if (best == null || best.version.compareTo(currentVersion) <= 0) {
            return new UpdateFeed(null, null, null, 0, null, List.of());
        }
        List<dev.infinia.store.contract.api.DeliveryDtos.AppUpdateArtifactDto> artifacts =
                new ArrayList<>();
        Instant expiresAt = Instant.now().plusSeconds(properties.downloadTicketTtlSeconds());
        for (Release.ArtifactInfo a : CompatibilityEvaluator.appArtifacts(best,
                requestedPlatform, requestedArch, requestedMode, variant)) {
            String ticketSignature = tickets.sign("download", a.blobKey(), expiresAt);
            String relativeUrl = "/api/v1/blobs/" + a.blobKey() + "?"
                    + TicketService.encodeTicketParams("download", a.blobKey(), expiresAt,
                            ticketSignature);
            artifacts.add(new dev.infinia.store.contract.api.DeliveryDtos.AppUpdateArtifactDto(
                    properties.baseUrl().replaceAll("/+$", "") + relativeUrl, a.filename(), a.sha256(),
                    a.signature(), a.keyId(), a.size(), a.platform().name().toLowerCase(),
                    a.arch().name().toLowerCase(), a.kind().name().toLowerCase(), a.variant(),
                    a.mimeType()));
        }
        return new UpdateFeed(appListing.coordinate().toString(), best.version.toString(),
                best.channel, best.rolloutPercent, best, artifacts);
    }

    private static ArtifactKind parseAppMode(String mode) {
        if (mode == null || mode.isBlank() || "any".equalsIgnoreCase(mode)) return null;
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "installer", "installed", "install" -> ArtifactKind.INSTALLER;
            case "portable" -> ArtifactKind.PORTABLE;
            default -> throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "mode must be installer, portable or any");
        };
    }

    /** Carrier for the update feed response; fields assembled in the controller. */
    public record UpdateFeed(String listingCoordinate, String latestVersion, Channel channel,
            int rolloutPercent, Release release,
            List<dev.infinia.store.contract.api.DeliveryDtos.AppUpdateArtifactDto> artifacts) {}

    // ---- envelope ----

    public ReleaseEnvelope buildEnvelope(Listing listing, Release release) {
        List<ArtifactRef> artifactRefs = new ArrayList<>();
        for (Release.ArtifactInfo a : release.artifacts) {
            artifactRefs.add(new ArtifactRef(
                    "/api/v1/blobs/" + a.blobKey(), a.sha256(),
                    a.signature(), a.keyId(), a.size(), a.platform().name().toLowerCase(),
                    a.arch().name().toLowerCase(), a.kind().name().toLowerCase(), a.variant(),
                    a.mimeType()));
        }
        return new ReleaseEnvelope(ReleaseEnvelope.CURRENT_SCHEMA_VERSION,
                listing.coordinate().withVersion(release.version).toString(),
                listing.type, release.version.toString(), release.channel, release.requiresHost,
                artifactRefs,
                release.dependencies.stream()
                        .map(d -> new dev.infinia.store.contract.envelope.DependencyRef(
                                d.coordinate(), d.range(), d.optional()))
                        .toList(),
                release.permissions.stream()
                        .map(p -> new dev.infinia.store.contract.envelope.PermissionRef(
                                p.permissionId(), p.scope(), p.required(), p.reason()))
                        .toList(),
                release.publishedAt == null ? null : release.publishedAt.toString());
    }

    public String canonicalJson(Object envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Envelope serialization failed", e);
        }
    }

    private Release latestCompatible(Listing listing, BrowseQuery query) {
        List<Release> visible = releases.findVisibleByListingId(listing.id);
        Release best = null;
        for (Release release : visible) {
            if (release.status != ReleaseStatus.PUBLISHED) {
                continue;
            }
            if (query.channel() != null && release.channel != query.channel) {
                continue;
            }
            if (query.hostVersion() != null
                    && !CompatibilityEvaluator.hostCompatible(release, query.hostVersion())) {
                continue;
            }
            if (query.os() != null || query.arch() != null) {
                var artifact = CompatibilityEvaluator.bestArtifact(release,
                        CompatibilityEvaluator.parsePlatform(query.os()),
                        CompatibilityEvaluator.parseArch(query.arch()));
                if (artifact.isEmpty()) {
                    continue;
                }
            }
            if (best == null || release.version.compareTo(best.version) > 0) {
                best = release;
            }
        }
        return best;
    }

    private CatalogItemDto toCatalogItem(Listing listing, Release latest) {
        String locale = "en";
        return new CatalogItemDto(
                listing.coordinate().toString(),
                listing.type.name(),
                listing.namespace,
                listing.slug,
                listing.name(locale),
                listing.summary(locale),
                listing.category,
                listing.tags,
                listing.iconUrl,
                latest == null ? null : latest.version.toString(),
                latest == null ? listing.defaultChannel.name().toLowerCase()
                        : latest.channel.name().toLowerCase(),
                listing.downloads,
                listing.namespace,
                latest == null || latest.publishedAt == null ? null
                        : latest.publishedAt.toString(),
                listing.featured,
                listing.minBeeLevel);
    }

}
