package dev.infinia.store.app.service;

import tools.jackson.databind.ObjectMapper;
import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.event.StoreEventPayloads;
import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.semver.SemVerRange;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.model.OutboxRecord;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.model.UploadSessionInfo;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.ReleaseStateMachine;
import dev.infinia.store.domain.service.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Publishing pipeline (design §8): create listing → draft release → upload session →
 * submit → async scan → review. Scan runs out-of-band so the API answers 202 quickly.
 */
@Service
public class PublisherService {

    private static final Logger log = LoggerFactory.getLogger(PublisherService.class);

    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final IdentityRepositories.OrganizationRepository organizations;
    private final PublishingRepositories.UploadSessionRepository uploads;
    private final PublishingRepositories.ReviewRepository reviews;
    private final PublishingRepositories.OutboxRepository outbox;
    private final dev.infinia.store.domain.port.BlobStorage blobs;
    private final StoreProperties properties;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final ScanPipeline scanPipeline;
    private final TransactionTemplate transactions;

    public PublisherService(ListingRepository listings, ReleaseRepository releases,
            IdentityRepositories.NamespaceRepository namespaces,
            IdentityRepositories.OrganizationRepository organizations,
            PublishingRepositories.UploadSessionRepository uploads,
            PublishingRepositories.ReviewRepository reviews,
            PublishingRepositories.OutboxRepository outbox,
            dev.infinia.store.domain.port.BlobStorage blobs, StoreProperties properties,
            ObjectMapper mapper, AuditService audit, ScanPipeline scanPipeline,
            TransactionTemplate transactions) {
        this.listings = listings;
        this.releases = releases;
        this.namespaces = namespaces;
        this.organizations = organizations;
        this.uploads = uploads;
        this.reviews = reviews;
        this.outbox = outbox;
        this.blobs = blobs;
        this.properties = properties;
        this.mapper = mapper;
        this.audit = audit;
        this.scanPipeline = scanPipeline;
        this.transactions = transactions;
    }

    // ---- listings ----

    @Transactional
    public Listing createListing(UUID publisherUserId, PublisherDtos.CreateListingRequest request) {
        return createListing(publisherUserId, false, request);
    }

    @Transactional
    public Listing createListing(UUID publisherUserId, boolean platformAdmin,
            PublisherDtos.CreateListingRequest request) {
        Namespace namespace = namespaces.findByName(request.namespace()).orElseThrow(
                () -> new DomainException(StoreErrorCode.NAMESPACE_NOT_OWNED,
                        "Unknown namespace: " + request.namespace()
                                + " — create it first via POST /api/v1/organizations"));
        // Namespaces are owned directly by a user or by an organization the user
        // belongs to (design §7.1). Platform admins may publish into any existing
        // namespace — they curate the whole catalog (design §7.4).
        boolean owned = platformAdmin || IdentityRepositories.ownsNamespace(namespaces,
                organizations, namespace, publisherUserId);
        if (!owned) {
            throw new DomainException(StoreErrorCode.NAMESPACE_NOT_OWNED,
                    "You do not own namespace " + request.namespace());
        }
        ListingType type;
        try {
            type = ListingType.valueOf(request.type().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "type must be one of APP, PLUGIN, SKILL, MCP, FLOW");
        }
        InfiniaCoordinate coordinate = InfiniaCoordinate.of(type, request.namespace(),
                request.slug());
        if (listings.existsByNamespaceAndSlugAndType(request.namespace(), request.slug(), type)) {
            throw new DomainException(StoreErrorCode.SLUG_TAKEN,
                    "Listing already exists: " + coordinate.listingPart());
        }
        Instant now = Instant.now();
        Listing listing = new Listing();
        listing.id = UuidV7.generate();
        listing.namespaceId = namespace.id();
        listing.namespace = namespace.name();
        listing.slug = request.slug();
        listing.type = type;
        listing.visibility = dev.infinia.store.contract.type.ListingVisibility.PUBLIC;
        listing.status = "ACTIVE";
        listing.category = request.category();
        listing.tags = request.tags() == null ? new ArrayList<>() : new ArrayList<>(request.tags());
        if (request.defaultChannel() != null && !request.defaultChannel().isBlank()) {
            try {
                listing.defaultChannel = Channel.parse(request.defaultChannel());
            } catch (IllegalArgumentException e) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED, e.getMessage());
            }
        } else {
            listing.defaultChannel = Channel.STABLE;
        }
        listing.publisherUserId = publisherUserId;
        listing.organizationId = namespace.organizationId();
        listing.minBeeLevel = normalizeMinBeeLevel(request.minBeeLevel());
        listing.createdAt = now;
        listing.updatedAt = now;
        listing.localizations = new ArrayList<>(List.of(new Listing.Localization(
                request.locale() == null ? "en" : request.locale(), request.name(),
                request.summary(), request.descriptionMarkdown(), null)));
        listings.save(listing);
        audit.record("USER", publisherUserId.toString(), "listing.create", "LISTING",
                listing.id.toString(), null, coordinate.toString(), null);
        enqueue(StoreEventPayloads.LISTING_CREATED, listing.id, "LISTING",
                toJson(new StoreEventPayloads.ListingCreated(coordinate.toString(),
                        type.name(), request.name())));
        return listing;
    }

    /**
     * Owner (or platform admin) adjustment of the listing's bee-level gate:
     * 0 = public for everyone, 1..4 = signed-in bees at or above the level.
     */
    @Transactional
    public Listing updateMinBeeLevel(UUID publisherUserId, UUID listingId, Integer minBeeLevel) {
        Listing listing = listings.findById(listingId).orElseThrow(
                () -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND, "Listing not found"));
        if (!listing.publisherUserId.equals(publisherUserId)) {
            throw DomainException.forbidden("You do not own this listing");
        }
        int normalized = normalizeMinBeeLevel(minBeeLevel);
        int before = listing.minBeeLevel;
        listing.minBeeLevel = normalized;
        listing.updatedAt = Instant.now();
        listings.save(listing);
        audit.record("USER", publisherUserId.toString(), "listing.minBeeLevel", "LISTING",
                listing.id.toString(), "L" + before, "L" + normalized, null);
        return listing;
    }

    private static int normalizeMinBeeLevel(Integer requested) {
        if (requested == null) {
            return 0;
        }
        if (!dev.infinia.store.contract.type.BeeLevel.isValid(requested)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Minimum Infinia Level (minBeeLevel) must be 0 (public) through "
                            + dev.infinia.store.contract.type.BeeLevel.MAX_LEVEL + " (QUEEN)");
        }
        return requested;
    }

    // ---- releases ----

    @Transactional
    public Release createDraftRelease(UUID publisherUserId, Listing listing,
            PublisherDtos.CreateReleaseRequest request) {
        return createDraftRelease(publisherUserId, false, listing, request);
    }

    /**
     * Platform admins curate the whole catalog and may draft releases on listings
     * they do not own (design §7.4) — the intranet admin manual-upload flow needs
     * this for the conventional host listing owned by the CI account.
     */
    @Transactional
    public Release createDraftRelease(UUID publisherUserId, boolean platformAdmin,
            Listing listing, PublisherDtos.CreateReleaseRequest request) {
        if (!platformAdmin) {
            requireListingOwnerByListingId(publisherUserId, listing.id);
        }
        SemVer version;
        try {
            version = SemVer.parse(request.version());
        } catch (IllegalArgumentException e) {
            throw new DomainException(StoreErrorCode.INVALID_SEMVER,
                    "version is not valid SemVer: " + request.version());
        }
        if (request.requiresHost() != null && !request.requiresHost().isBlank()) {
            try {
                SemVerRange.parse(request.requiresHost());
            } catch (IllegalArgumentException e) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "requiresHost is not a valid SemVer range");
            }
            // The host's range parser rejects npm shorthands — gate them at publish
            // time so approved releases always install (FengYuHostRules mirror).
            if (!dev.infinia.store.scanner.FengYuHostRules
                    .hostCompatibleRange(request.requiresHost())) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                        "requiresHost must use host-compatible range syntax "
                                + "(>= <= > < = only; no ^ ~ x *)");
            }
        }
        // Duplicate-version gate aligned with the DB unique index (listing_id, version):
        // EVERY status occupies its slot — yanked/deprecated/rejected included — so the
        // application check never waves a row through that the index then rejects with
        // a raw 500 (audit P2-9). SemVer equality also catches versions that differ
        // only in build metadata (4.0.0 vs 4.0.0+build): the strings are distinct, so
        // the index would allow them, but they tie on precedence and break "latest".
        if (releases.findByListingId(listing.id).stream()
                .anyMatch(existing -> existing.version.equals(version))) {
            throw new DomainException(StoreErrorCode.DUPLICATE_VERSION,
                    "Version " + version + " already exists for this listing"
                            + " (build-metadata-only variants are not distinct)");
        }
        Release release = new Release();
        release.id = UuidV7.generate();
        release.listingId = listing.id;
        release.version = version;
        release.status = ReleaseStatus.DRAFT;
        Channel requestedChannel = listing.defaultChannel;
        if (request.channel() != null && !request.channel().isBlank()) {
            try {
                requestedChannel = Channel.parse(request.channel());
            } catch (IllegalArgumentException e) {
                throw new DomainException(StoreErrorCode.VALIDATION_FAILED, e.getMessage());
            }
        }
        release.channel = requestedChannel;
        release.createdAt = Instant.now();
        release.requiresHost = request.requiresHost();
        release.license = request.license();
        release.sourceUrl = request.sourceUrl();
        release.changelogMarkdown = request.changelogMarkdown();
        release.rolloutPercent = request.rolloutPercent() == null ? 100
                : Math.max(0, Math.min(100, request.rolloutPercent()));
        if (request.dependencies() != null) {
            // Same host-compatibility gate as requiresHost: the FengYu host's range
            // parser only accepts >= <= > < = comparators. A release shipping ^/~
            // shorthands would resolve here but fail at install time on the host —
            // approval must imply installability (audit P3).
            for (dev.infinia.store.contract.api.ListingDtos.DependencyDto dep
                    : request.dependencies()) {
                if (dep.range() == null || dep.range().isBlank()) {
                    continue;
                }
                try {
                    SemVerRange.parse(dep.range());
                } catch (IllegalArgumentException e) {
                    throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                            "dependencies[" + dep.coordinate() + "].range is not a valid"
                                    + " SemVer range: " + dep.range());
                }
                if (!dev.infinia.store.scanner.FengYuHostRules
                        .hostCompatibleRange(dep.range())) {
                    throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                            "dependencies[" + dep.coordinate() + "].range must use"
                                    + " host-compatible syntax (>= <= > < = only;"
                                    + " no ^ ~ x *): " + dep.range());
                }
            }
            release.dependencies = request.dependencies().stream()
                    .map(d -> new Release.DependencyDecl(d.coordinate(), d.range(), d.optional()))
                    .toList();
        }
        if (request.permissions() != null) {
            release.permissions = request.permissions().stream()
                    .map(p -> new Release.PermissionDecl(p.permissionId(), p.scope(),
                            p.required(), p.reason()))
                    .toList();
        }
        releases.save(release);
        audit.record("USER", publisherUserId.toString(), "release.create", "RELEASE",
                release.id.toString(), null, version.toString(), null);
        return release;
    }

    /**
     * Attaches a pass-through artifact for upstream-aggregated releases: provenance
     * lives in upstream_item, bytes are rebuilt from the upstream at download time
     * — nothing is persisted to blob storage (aggregation plan §5.2).
     */
    @Transactional
    public Release attachVirtualArtifact(UUID publisherUserId, Release release,
            Release.ArtifactInfo artifact) {
        requireListingOwner(publisherUserId, release);
        release.artifacts = new ArrayList<>(release.artifacts);
        release.artifacts.add(artifact);
        releases.save(release);
        return release;
    }

    public UploadSessionInfo createUploadSession(UUID publisherUserId, Release release,
            String filename, ArtifactKind kind, Platform platform, Arch arch, String variant,
            long declaredSize) {
        return createUploadSession(publisherUserId, false, release, filename, kind, platform,
                arch, variant, declaredSize);
    }

    /** Admin variant: skips the listing-ownership check (see createDraftRelease). */
    public UploadSessionInfo createUploadSession(UUID publisherUserId, boolean platformAdmin,
            Release release, String filename, ArtifactKind kind, Platform platform, Arch arch,
            String variant, long declaredSize) {
        if (!platformAdmin) {
            requireListingOwner(publisherUserId, release);
        }
        if (filename == null || filename.isBlank()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "filename is required");
        }
        if (declaredSize > properties.maxUploadBytes()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Declared size exceeds the upload limit");
        }
        if (release.status != ReleaseStatus.DRAFT && release.status != ReleaseStatus.UPLOADING
                && release.status != ReleaseStatus.REJECTED
                && release.status != ReleaseStatus.CHANGES_REQUESTED) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.UPLOADING);
        }
        UploadSessionInfo session = new UploadSessionInfo();
        Listing listing = listings.findById(release.listingId).orElseThrow(
                () -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        session.id = UuidV7.generate();
        session.releaseId = release.id;
        session.filename = filename;
        session.kind = kind == null
                ? (listing.type == ListingType.APP ? inferAppKind(filename) : ArtifactKind.PACKAGE)
                : kind;
        if (listing.type == ListingType.APP && session.kind == ArtifactKind.PACKAGE) {
            // Older publisher clients omitted kind for APP uploads. Treat their
            // primary binary as a portable distribution instead of creating an
            // APP release that the update feed cannot route.
            session.kind = ArtifactKind.PORTABLE;
        }
        if (listing.type == ListingType.APP && session.kind != ArtifactKind.INSTALLER
                && session.kind != ArtifactKind.PORTABLE
                && session.kind != ArtifactKind.CHECKSUMS
                && session.kind != ArtifactKind.SIGNATURE
                && session.kind != ArtifactKind.SBOM) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "APP artifacts must be INSTALLER, PORTABLE, CHECKSUMS, SIGNATURE or SBOM");
        }
        session.platform = platform == null
                ? (listing.type == ListingType.APP ? inferPlatform(filename) : Platform.UNIVERSAL)
                : platform;
        session.arch = arch == null
                ? (listing.type == ListingType.APP ? inferArch(filename) : Arch.UNIVERSAL)
                : arch;
        session.variant = normalizeVariant(variant, listing.type, filename);
        session.declaredSize = declaredSize;
        session.status = "PENDING";
        session.expiresAt = Instant.now().plusSeconds(properties.uploadTicketTtlSeconds());
        uploads.save(session);
        return session;
    }

    /**
     * Receives the presigned PUT: streams to blob storage, computes SHA-256 and
     * attaches the artifact to the release (design §8.2 steps 1-2). The body is
     * streamed and digested BEFORE any transaction opens — a slow upload must
     * not pin a pooled database connection — and the session is then claimed
     * atomically, so concurrent replays of one presigned URL cannot attach the
     * artifact twice.
     */
    public UploadSessionInfo completeUpload(UUID uploadId, InputStream body) throws IOException {
        UploadSessionInfo session = uploads.findById(uploadId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "Upload session not found"));
        if (session.expired(Instant.now())) {
            session.status = "EXPIRED";
            uploads.save(session);
            throw new DomainException(StoreErrorCode.UPLOAD_EXPIRED, "Upload session expired");
        }
        if (!"PENDING".equals(session.status)) {
            throw new DomainException(StoreErrorCode.UPLOAD_NOT_COMPLETE,
                    "Upload session already used");
        }
        String blobKey = blobs.put(body, properties.maxUploadBytes(), null);
        long size = blobs.size(blobKey);
        String sha256 = sha256Of(blobKey);
        String mimeType = mimeType(session.filename);
        // A declared size is a commitment: accepting a different body would let a
        // client smuggle arbitrary sizes past the upload limit checks that ran at
        // session creation (audit P3).
        if (session.declaredSize > 0 && session.declaredSize != size) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Uploaded size " + size + " does not match the declared size "
                            + session.declaredSize + " — restart the upload session");
        }
        UploadSessionInfo completed = transactions.execute(txStatus -> finalizeUpload(uploadId,
                blobKey, size, sha256, mimeType));
        if ("VOIDED".equals(completed.status)) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Upload session was voided: the release already left the editing"
                            + " phase — create a new release for further changes");
        }
        return completed;
    }

    /** Recovers the digest from a content-addressed key ({@code sha256/<2>/<62>}). */
    private static String sha256Of(String blobKey) {
        int digestPrefix = blobKey.indexOf("sha256/");
        if (digestPrefix < 0) {
            return blobKey.substring(blobKey.lastIndexOf('/') + 1);
        }
        return blobKey.substring(digestPrefix + "sha256/".length()).replace("/", "");
    }

    private UploadSessionInfo finalizeUpload(UUID uploadId, String blobKey, long size,
            String sha256, String mimeType) {
        UploadSessionInfo session = uploads.findById(uploadId).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND, "Upload session not found"));
        if (session.expired(Instant.now())) {
            session.status = "EXPIRED";
            uploads.save(session);
            throw new DomainException(StoreErrorCode.UPLOAD_EXPIRED, "Upload session expired");
        }
        if (!uploads.claimForCompletion(uploadId)) {
            throw new DomainException(StoreErrorCode.UPLOAD_NOT_COMPLETE,
                    "Upload session already used");
        }

        Release release = releases.findById(session.releaseId).orElseThrow(
                () -> new DomainException(StoreErrorCode.RELEASE_NOT_FOUND, "Release missing"));
        // State-machine gate (audit P1-2): uploads may only complete while the
        // release is still being edited. A session issued before submit must not
        // smuggle unscanned artifacts into a release that already entered the
        // scan/review pipeline. The session is voided durably (throwing here would
        // roll the claim back and leave it replayable); completeUpload translates
        // the voided state into a 409 for the client.
        if (release.status != ReleaseStatus.DRAFT
                && release.status != ReleaseStatus.UPLOADING
                && release.status != ReleaseStatus.REJECTED
                && release.status != ReleaseStatus.CHANGES_REQUESTED) {
            session.status = "VOIDED";
            uploads.save(session);
            return session;
        }
        if (release.status == ReleaseStatus.REJECTED
                || release.status == ReleaseStatus.CHANGES_REQUESTED) {
            // Explicit rework transition (audit P1-3): re-uploading moves the
            // release back into the editing path so it can be submitted again.
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.DRAFT);
            release.status = ReleaseStatus.DRAFT;
        }
        if (release.status == ReleaseStatus.DRAFT) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.UPLOADING);
            release.status = ReleaseStatus.UPLOADING;
        }
        // Same-route replacement semantics (audit P1-3): a re-upload after
        // CHANGES_REQUESTED targets the same (platform, arch, kind, variant) slot —
        // replacing the row keeps the pk_release_artifact contract instead of
        // dying on a duplicate-key 500 with no artifact-delete API to recover.
        boolean replaced = false;
        List<Release.ArtifactInfo> artifacts = new ArrayList<>();
        for (Release.ArtifactInfo a : release.artifacts) {
            if (a.platform() == session.platform && a.arch() == session.arch
                    && a.kind() == session.kind
                    && java.util.Objects.equals(a.variant(), session.variant)) {
                if (!replaced) {
                    artifacts.add(new Release.ArtifactInfo(UuidV7.generate(), session.kind,
                            session.platform, session.arch, session.variant, session.filename,
                            size, sha256, null, null, blobKey, mimeType));
                    replaced = true;
                }
                // A duplicate legacy route drops out — one artifact per slot.
            } else {
                artifacts.add(a);
            }
        }
        if (!replaced) {
            artifacts.add(new Release.ArtifactInfo(UuidV7.generate(), session.kind,
                    session.platform, session.arch, session.variant, session.filename, size,
                    sha256, null, null, blobKey, mimeType));
        }
        release.artifacts = artifacts;
        releases.save(release);

        session.status = "COMPLETED";
        session.blobKey = blobKey;
        session.sha256 = sha256;
        session.mimeType = mimeType;
        uploads.save(session);
        return session;
    }

    // ---- submit + scan ----

    @Transactional
    public Review submit(UUID publisherUserId, Release release) {
        requireListingOwner(publisherUserId, release);
        List<UploadSessionInfo> sessions = uploads.findByReleaseId(release.id);
        if (sessions.stream().noneMatch(s -> "COMPLETED".equals(s.status))) {
            throw new DomainException(StoreErrorCode.UPLOAD_NOT_COMPLETE,
                    "Upload the package before submitting");
        }
        Listing listing = listings.findById(release.listingId).orElseThrow(
                () -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        if (listing.type == ListingType.APP && release.artifacts.stream().noneMatch(
                a -> a.kind() == ArtifactKind.INSTALLER
                        || a.kind() == ArtifactKind.PORTABLE)) {
            throw new DomainException(StoreErrorCode.UPLOAD_NOT_COMPLETE,
                    "Upload at least one APP installer or portable artifact before submitting");
        }
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.SCANNING);
        release.status = ReleaseStatus.SCANNING;
        releases.save(release);

        Review review = new Review();
        review.id = UuidV7.generate();
        review.releaseId = release.id;
        review.listingId = release.listingId;
        review.status = "IN_REVIEW";
        review.submittedAt = Instant.now();
        review.findings = new ArrayList<>();
        reviews.save(review);
        audit.record("USER", publisherUserId.toString(), "release.submit", "RELEASE",
                release.id.toString(), null, release.status.name(), null);

        // The async scan must not race its own trigger: reading the new SCANNING
        // row before this transaction commits made the pipeline wait on a 2s poll
        // (or wedge forever when it lost the race). Registering an afterCommit
        // synchronization hands the release+review over only once they are durable
        // (audit P2-6); a scheduled watchdog still reconciles anything stuck.
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    scanPipeline.runScan(release.id, review.id);
                                }
                            });
        } else {
            scanPipeline.runScan(release.id, review.id);
        }
        return review;
    }

    // ---- helpers ----

    public void requireListingOwner(UUID publisherUserId, Release release) {
        Listing listing = listings.findById(release.listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        if (!listing.publisherUserId.equals(publisherUserId)) {
            throw DomainException.forbidden("You do not own this listing");
        }
    }

    /** Ownership check keyed by listing id (used before a release is created). */
    public void requireListingOwnerByListingId(UUID publisherUserId, UUID listingId) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        if (!listing.publisherUserId.equals(publisherUserId)) {
            throw DomainException.forbidden("You do not own this listing");
        }
    }

    private void enqueue(String type, UUID aggregateId, String aggregateType, String payloadJson) {
        outbox.enqueue(new OutboxRecord(UuidV7.generate(), aggregateType, aggregateId.toString(),
                type, payloadJson, OutboxRecord.STATUS_PENDING, 0, Instant.now(), Instant.now()));
    }

    private static ArtifactKind inferAppKind(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".dmg")
                || lower.endsWith(".pkg") || lower.endsWith(".deb")
                ? ArtifactKind.INSTALLER : ArtifactKind.PORTABLE;
    }

    private static Platform inferPlatform(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("win32") || lower.contains("windows") || lower.contains("win-")
                || lower.endsWith(".exe") || lower.endsWith(".msi")) {
            return Platform.WINDOWS;
        }
        if (lower.contains("mac-") || lower.contains("macos") || lower.endsWith(".dmg")
                || lower.endsWith(".pkg")) {
            return Platform.MACOS;
        }
        if (lower.contains("linux") || lower.endsWith(".deb")
                || lower.endsWith(".appimage")) {
            return Platform.LINUX;
        }
        return Platform.UNIVERSAL;
    }

    /** Desktop distributions declare their architecture in the release filename. */
    private static Arch inferArch(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("arm64") || lower.contains("aarch64")) {
            return Arch.ARM64;
        }
        if (lower.contains("x64") || lower.contains("x86_64") || lower.contains("amd64")) {
            return Arch.X64;
        }
        return Arch.UNIVERSAL;
    }

    private static String normalizeVariant(String requested, ListingType type, String filename) {
        String value = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank()) {
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            value = type != ListingType.APP ? "default"
                    : lower.equals("infinia.jar") ? "jar"
                    : lower.contains("uos") ? "uos"
                    : lower.contains("jre") ? "jre"
                    : lower.contains("-web.") ? "web" : "lite";
        }
        if (!value.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "variant must contain only lowercase letters, digits and hyphens");
        }
        return value;
    }

    private static String mimeType(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".sig") || lower.endsWith(".yml")
                || lower.endsWith(".yaml")) return "text/plain";
        if (lower.endsWith(".zip") || lower.endsWith(".fyp") || lower.endsWith(".fys")) {
            return "application/zip";
        }
        if (lower.endsWith(".jar")) return "application/java-archive";
        if (lower.endsWith(".dmg")) return "application/x-apple-diskimage";
        if (lower.endsWith(".deb")) return "application/vnd.debian.binary-package";
        return "application/octet-stream";
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

}
