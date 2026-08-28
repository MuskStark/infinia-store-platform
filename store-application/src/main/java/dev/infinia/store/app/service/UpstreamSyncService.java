package dev.infinia.store.app.service;

import dev.infinia.store.app.upstream.ClaudeMarketplaceAdapter;
import dev.infinia.store.app.upstream.McpRegistryAdapter;
import dev.infinia.store.app.upstream.RepoFetcher;
import dev.infinia.store.app.upstream.SkillRepositoryAdapter;
import dev.infinia.store.app.upstream.UpstreamAdapter;
import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.model.SyncRun;
import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamRelease;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.port.UpstreamRepositories;
import dev.infinia.store.domain.service.ReleaseStateMachine;
import dev.infinia.store.domain.service.UuidV7;
import dev.infinia.store.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sync orchestrator (aggregation plan §3/§4): adapters discover and normalize
 * upstream entries; every item then flows through the standard publish
 * pipeline (scan → review → sign) with full provenance — source URL, path,
 * commit sha, content digest — recorded in upstream_item / upstream_release.
 * Idempotency is the exact (source, externalId, contentSha256) triple, so a
 * re-sync of unchanged content imports nothing.
 */
@Service
public class UpstreamSyncService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamSyncService.class);

    private final PublishingRepositories.UpstreamSourceRepository upstreams;
    private final UpstreamRepositories.UpstreamItemRepository upstreamItems;
    private final UpstreamRepositories.UpstreamReleaseRepository upstreamReleases;
    private final UpstreamRepositories.SyncRunRepository syncRuns;
    private final PublisherService publisher;
    private final ReviewService reviews;
    private final PublishingRepositories.ReviewRepository reviewRepository;
    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final AuditService audit;
    private final RepoFetcher fetcher;
    private final dev.infinia.store.app.upstream.UpstreamPackageBuilder packageBuilder;
    private final List<UpstreamAdapter> adapters;

    public UpstreamSyncService(PublishingRepositories.UpstreamSourceRepository upstreams,
            UpstreamRepositories.UpstreamItemRepository upstreamItems,
            UpstreamRepositories.UpstreamReleaseRepository upstreamReleases,
            UpstreamRepositories.SyncRunRepository syncRuns,
            PublisherService publisher, ReviewService reviews,
            PublishingRepositories.ReviewRepository reviewRepository,
            ListingRepository listings, ReleaseRepository releases,
            IdentityRepositories.UserRepository users,
            IdentityRepositories.NamespaceRepository namespaces, AuditService audit,
            RepoFetcher fetcher,
            dev.infinia.store.app.upstream.UpstreamPackageBuilder packageBuilder,
            ClaudeMarketplaceAdapter claude,
            SkillRepositoryAdapter skillRepo, McpRegistryAdapter mcpRegistry) {
        this.upstreams = upstreams;
        this.upstreamItems = upstreamItems;
        this.upstreamReleases = upstreamReleases;
        this.syncRuns = syncRuns;
        this.publisher = publisher;
        this.reviews = reviews;
        this.reviewRepository = reviewRepository;
        this.listings = listings;
        this.releases = releases;
        this.users = users;
        this.namespaces = namespaces;
        this.audit = audit;
        this.fetcher = fetcher;
        this.packageBuilder = packageBuilder;
        this.adapters = List.of(claude, skillRepo, mcpRegistry);
    }

    public record SyncResult(String upstream, int imported, int skipped, int failed,
            List<String> errors) {}

    // Deliberately not @Transactional: the review wait polls for progress committed
    // by the async scan worker; each publisher/review call manages its own transaction.
    public SyncResult sync(UUID upstreamId) {
        UpstreamSource source = upstreams.findById(upstreamId)
                .orElseThrow(() -> new DomainException(dev.infinia.store.contract.error
                        .StoreErrorCode.NOT_FOUND, "Upstream source not found"));
        SyncRun run = new SyncRun(UuidV7.generate(), source.id(), Instant.now(), null,
                0, 0, 0, "RUNNING", null);
        syncRuns.save(run);
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        try {
            StoreUser bot = requireCiAccount();
            StoreUser reviewer = requireReviewerAccount();
            ensureNamespace(source.targetNamespace(), bot);

            for (NormalizedItem item : resolveAdapter(source).discover(source, fetcher)) {
                try {
                    String contentSha = contentDigest(item);
                    var exact = upstreamItems.findExact(source.id(), item.externalId(),
                            contentSha);
                    if (exact.isPresent()) {
                        if (isVirtual(latestPublished(source, item))) {
                            skipped++;
                            continue; // unchanged and already pass-through
                        }
                        // Legacy blob-backed release with identical content: convert by
                        // publishing the pass-through form once (aggregation plan §5.2).
                        Release published = publish(source, bot, reviewer, item, contentSha,
                                exact.get().id());
                        upstreamReleases.save(new UpstreamRelease(UuidV7.generate(),
                                exact.get().id(), published.id, exact.get().commitSha(),
                                item.version(), sha256Hex(buildArtifact(source, item,
                                        published.version.toString())), run.id(),
                                Instant.now()));
                        imported++;
                        continue;
                    }
                    // Pre-provenance migration for entries imported before tracking.
                    Release existing = latestPublished(source, item);
                    if (existing != null
                            && upstreamItems.findLatest(source.id(), item.externalId())
                                    .isEmpty()) {
                        recordProvenance(source, item, contentSha, existing, run.id());
                        skipped++;
                        continue;
                    }
                    UUID virtualArtifactId = UuidV7.generate();
                    Release published = publish(source, bot, reviewer, item, contentSha,
                            virtualArtifactId);
                    recordProvenance(source, item, contentSha, published, run.id(),
                            virtualArtifactId);
                    imported++;
                } catch (Exception e) {
                    errors.add(item.slug() + ": " + e.getMessage());
                    log.warn("Upstream item failed: {}", e.toString());
                }
            }
            upstreams.save(withStatus(source, errors.isEmpty(), String.join("; ", errors)));
            syncRuns.save(finished(run, imported, skipped, errors));
            audit.record("SERVICE", "upstream-sync", "upstream.sync", "UPSTREAM",
                    source.id().toString(), null,
                    "imported=" + imported + ",skipped=" + skipped + ",failed=" + errors.size(),
                    null);
        } catch (Exception e) {
            errors.add("sync aborted: " + e.getMessage());
            upstreams.save(withStatus(source, false, String.join("; ", errors)));
            syncRuns.save(finished(run, imported, skipped, errors));
            log.warn("Upstream sync aborted: {}", e.toString());
        }
        return new SyncResult(source.name(), imported, skipped, errors.size(), errors);
    }

    /** Latest published release of an entry's listing, if the listing exists. */
    private Release latestPublished(UpstreamSource source, NormalizedItem item) {
        ListingType type = "SKILL".equals(item.kind()) ? ListingType.SKILL : ListingType.MCP;
        return listings.findByCoordinate(InfiniaCoordinate.of(type,
                        source.targetNamespace(), item.slug()))
                .flatMap(l -> releases.findLatestVisible(l.id, Channel.STABLE)
                        .filter(r -> r.status == ReleaseStatus.PUBLISHED))
                .orElse(null);
    }

    // ---- adapter resolution (AUTO probes the document shape) ----

    private UpstreamAdapter resolveAdapter(UpstreamSource source) throws IOException {
        String requested = source.adapterType() == null || source.adapterType().isBlank()
                ? UpstreamAdapter.AUTO : source.adapterType().trim().toUpperCase();
        if (!UpstreamAdapter.AUTO.equals(requested)) {
            return adapters.stream().filter(a -> a.type().equals(requested)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown adapter type " + requested));
        }
        try {
            var node = fetcher.fetchJson(source.marketplaceUrl());
            if (node.has("remotes") || node.has("packages")) {
                return adapters.get(2); // MCP registry server.json
            }
        } catch (Exception ignored) {
            // repo-style source — default to the Claude marketplace adapter,
            // which also sweeps the repository for unlisted skill directories.
        }
        return adapters.get(0);
    }

    // ---- publishing (plan §8: nothing bypasses scan → review → sign) ----

    /**
     * Publishes one upstream item with a pass-through (virtual) artifact: no blob
     * is stored — the provenance row is the artifact, downloads rebuild from the
     * upstream and verify against the recorded content digest. Scanning runs
     * in-memory on the exact bytes a download would produce.
     */
    private Release publish(UpstreamSource source, StoreUser bot, StoreUser reviewer,
            NormalizedItem item, String contentSha, UUID virtualArtifactId)
            throws IOException, InterruptedException {
        boolean isSkill = "SKILL".equals(item.kind());
        ListingType type = isSkill ? ListingType.SKILL : ListingType.MCP;
        String artifactName = isSkill ? item.slug() + ".fys" : item.slug() + ".json";

        InfiniaCoordinate coordinate = InfiniaCoordinate.of(type,
                source.targetNamespace(), item.slug());
        Listing listing = listings.findByCoordinate(coordinate).orElse(null);
        if (listing == null) {
            listing = publisher.createListing(bot.id, new PublisherDtos.CreateListingRequest(
                    source.targetNamespace(), item.slug(), type.name(), "Aggregated",
                    List.of("upstream"), "stable", item.name(),
                    item.description().isBlank()
                            ? "Aggregated from " + source.name() : item.description(),
                    null, "en"));
        }

        Release release = allocateVersion(bot, listing, baseVersion(item), item.sourceUrl(),
                source);
        byte[] artifact = buildArtifact(source, item, release.version.toString());
        publisher.attachVirtualArtifact(bot.id, release, new Release.ArtifactInfo(
                UuidV7.generate(), ArtifactKind.PACKAGE, Platform.UNIVERSAL, Arch.UNIVERSAL,
                artifactName, artifact.length, sha256Hex(artifact), null, null,
                "upstream/" + virtualArtifactId, "application/octet-stream"));
        return scanAndApprove(source, bot, reviewer, release, artifact, type);
    }

    /** In-memory scan on the exact pass-through bytes; blocking → fast-fail. */
    private Release scanAndApprove(UpstreamSource source, StoreUser bot, StoreUser reviewer,
            Release release, byte[] artifact, ListingType type) {
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.UPLOADING);
        release.status = ReleaseStatus.UPLOADING;
        releases.save(release);
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.SCANNING);
        release.status = ReleaseStatus.SCANNING;
        releases.save(release);

        ScanResult result = new dev.infinia.store.scanner.PackageScanner()
                .scan(type.name(), release.version.toString(), artifact);
        Review review = new Review();
        review.id = UuidV7.generate();
        review.releaseId = release.id;
        review.listingId = release.listingId;
        review.submittedAt = Instant.now();
        review.findings = new ArrayList<>(result.findings.stream()
                .map(f -> new Review.Finding(f.severity(), f.rule(), f.message())).toList());
        if (result.hasBlockingFindings()) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.REJECTED);
            release.status = ReleaseStatus.REJECTED;
            review.status = "REJECTED";
            reviewRepository.save(review);
            releases.save(release);
            String rules = result.findings.stream().map(ScanResult.Finding::rule).distinct()
                    .toList().toString();
            throw new IllegalStateException("blocked by security scan " + rules
                    + " — an admin can force-publish after manual review");
        }
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.IN_REVIEW);
        release.status = ReleaseStatus.IN_REVIEW;
        review.status = "IN_REVIEW";
        reviewRepository.save(review);
        releases.save(release);
        reviews.decide(reviewer.id, review.id,
                new dev.infinia.store.contract.api.ReviewDtos.ReviewDecisionRequest("APPROVE",
                        "Auto-approved: aggregated from trusted upstream " + source.name()));
        return releases.findById(release.id).orElse(release);
    }

    private byte[] buildArtifact(UpstreamSource source, NormalizedItem item, String version)
            throws IOException {
        return "SKILL".equals(item.kind())
                ? packageBuilder.buildSkillPackage(source.targetNamespace(), item.slug(),
                        item.name(), item.description(), item.skillFiles(), version)
                : item.mcpTemplate();
    }

    private static String sha256Hex(byte[] bytes) {
        return dev.infinia.store.scanner.Ed25519Signer.sha256Hex(bytes);
    }

    /**
     * Bump-and-rebuild loop keeps the shipped manifest version consistent and
     * strictly above the highest existing version (a conversion must become the
     * latest release, not slot in below it).
     */
    private Release allocateVersion(StoreUser bot, Listing listing, String baseVersion,
            String sourceUrl, UpstreamSource source) {
        var floor = releases.findByListingId(listing.id).stream()
                .map(r -> r.version)
                .filter(v -> v != null)
                .max(dev.infinia.store.contract.semver.SemVer::compareTo)
                .orElse(null);
        DomainException lastConflict = null;
        for (int patch = 0; patch < 50; patch++) {
            String version = bump(baseVersion, patch);
            if (floor != null
                    && dev.infinia.store.contract.semver.SemVer.parse(version)
                            .compareTo(floor) <= 0) {
                continue; // never publish below or equal to an existing version
            }
            try {
                return publisher.createDraftRelease(bot.id, listing,
                        new PublisherDtos.CreateReleaseRequest(version, "stable", null, null,
                                sourceUrl, "Aggregated from upstream " + source.name(), null,
                                null, 100));
            } catch (DomainException e) {
                if ("duplicate_version".equals(e.code.code)) {
                    lastConflict = e;
                    continue;
                }
                throw e;
            }
        }
        throw lastConflict != null ? lastConflict
                : new DomainException(dev.infinia.store.contract.error.StoreErrorCode
                        .VALIDATION_FAILED, "could not allocate a version");
    }

    private void recordProvenance(UpstreamSource source, NormalizedItem item, String contentSha,
            Release published, UUID runId) {
        recordProvenance(source, item, contentSha, published, runId, UuidV7.generate());
    }

    private void recordProvenance(UpstreamSource source, NormalizedItem item, String contentSha,
            Release published, UUID runId, UUID virtualArtifactId) {
        Instant now = Instant.now();
        String commitSha = commitShaOf(item);
        UpstreamItem record = new UpstreamItem(virtualArtifactId, source.id(),
                item.externalId(), published.listingId, item.sourceUrl(), item.sourcePath(),
                null, commitSha, item.version(), contentSha, now, now, null);
        upstreamItems.save(record);
        upstreamReleases.save(new UpstreamRelease(UuidV7.generate(), record.id(),
                published.id, commitSha, item.version(),
                published.artifacts.isEmpty() ? contentSha
                        : published.artifacts.get(0).sha256(),
                runId, now));
    }

    private static boolean isVirtual(Release release) {
        return release != null && !release.artifacts.isEmpty()
                && release.artifacts.get(0).blobKey() != null
                && release.artifacts.get(0).blobKey().startsWith("upstream/");
    }

    private String commitShaOf(NormalizedItem item) {
        if (item.sourceUrl() == null
                || !item.sourceUrl().startsWith("https://github.com/")) {
            return null;
        }
        try {
            return fetcher.commitSha(item.sourceUrl(), null, new RepoFetcher.SyncScope());
        } catch (Exception e) {
            return null; // provenance degrades to ref-only, never blocks the sync
        }
    }

    private String contentDigest(NormalizedItem item) {
        return packageBuilder.contentDigest(item.skillFiles(), item.mcpTemplate());
    }

    /** Polls until the scan resolves: into review, or rejected with reasons. */
    private void awaitReview(UUID releaseId) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Release current = releases.findById(releaseId).orElse(null);
            if (current != null && current.status == ReleaseStatus.REJECTED) {
                String findings = reviewRepository.findLatestByReleaseId(releaseId)
                        .stream().flatMap(r -> r.findings.stream())
                        .map(f -> f.rule()).distinct().toList().toString();
                throw new IllegalStateException("blocked by security scan " + findings
                        + " — an admin can force-publish after manual review");
            }
            if (current != null && current.status == ReleaseStatus.IN_REVIEW) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("scan did not reach IN_REVIEW in time");
    }

    private static String baseVersion(NormalizedItem item) {
        String v = item.version();
        return v != null && dev.infinia.store.contract.semver.SemVer.isValid(v) ? v : "0.0.0";
    }

    private static String bump(String base, int patch) {
        if (patch == 0) {
            return base;
        }
        String[] parts = base.split("[-+]");
        String[] numbers = parts[0].split("\\.");
        long minor = numbers.length > 1 ? Long.parseLong(numbers[1]) : 0;
        return numbers[0] + "." + (minor + patch) + ".0";
    }

    private UpstreamSource withStatus(UpstreamSource source, boolean ok, String error) {
        return new UpstreamSource(source.id(), source.name(), source.marketplaceUrl(),
                source.targetNamespace(), source.enabled(), Instant.now(), ok,
                ok ? null : error, source.adapterType());
    }

    private SyncRun finished(SyncRun run, int imported, int skipped, List<String> errors) {
        return new SyncRun(run.id(), run.sourceId(), run.startedAt(), Instant.now(),
                imported, skipped, errors.size(), errors.isEmpty() ? "OK" : "PARTIAL",
                errors.isEmpty() ? null : String.join("; ", errors));
    }

    private StoreUser requireCiAccount() {
        return users.findByEmailNormalized("ci@infinia.local")
                .orElseThrow(() -> new IllegalStateException(
                        "CI publisher account missing (seed required)"));
    }

    private StoreUser requireReviewerAccount() {
        return users.findByEmailNormalized("reviewer@infinia.local")
                .orElseThrow(() -> new IllegalStateException(
                        "Reviewer account missing (seed required)"));
    }

    private void ensureNamespace(String name, StoreUser owner) {
        if (namespaces.findByName(name).isEmpty()) {
            namespaces.save(new Namespace(UuidV7.generate(), name, owner.id, null, false,
                    Instant.now()));
        }
    }
}
