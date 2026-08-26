package dev.infinia.store.app.service;

import tools.jackson.databind.ObjectMapper;
import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.api.ReviewDtos.ReviewDecisionRequest;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.event.StoreEventPayloads;
import dev.infinia.store.contract.type.ReviewDecisionType;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.OutboxRecord;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.ReleaseStateMachine;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Human review decisions and security withdrawals (design §8.1, §7.3). Approval
 * platform-signs the release envelope, publishes the release and emits the outbox
 * event consumed by webhooks and the search indexer.
 */
@Service
public class ReviewService {

    private final PublishingRepositories.ReviewRepository reviews;
    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final PublishingRepositories.OutboxRepository outbox;
    private final PlatformSigningService signing;
    private final CatalogService catalog;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ReviewService(PublishingRepositories.ReviewRepository reviews,
            ReleaseRepository releases, ListingRepository listings,
            PublishingRepositories.OutboxRepository outbox, PlatformSigningService signing,
            CatalogService catalog, AuditService audit, ObjectMapper mapper) {
        this.reviews = reviews;
        this.releases = releases;
        this.listings = listings;
        this.outbox = outbox;
        this.signing = signing;
        this.catalog = catalog;
        this.audit = audit;
        this.mapper = mapper;
    }

    public List<Review> queue(String status) {
        return reviews.findByStatus(status == null ? "IN_REVIEW" : status, 100);
    }

    @Transactional
    public Review decide(UUID reviewerUserId, UUID reviewId, ReviewDecisionRequest request) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Review not found"));
        Release release = releases.findById(review.releaseId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.RELEASE_NOT_FOUND,
                        "Release not found"));
        Listing listing = listings.findById(release.listingId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.LISTING_NOT_FOUND,
                        "Listing not found"));
        // A reviewer must never approve their own release (design §7.3).
        if (reviewerUserId != null && reviewerUserId.equals(listing.publisherUserId)) {
            throw new DomainException(StoreErrorCode.SELF_REVIEW_FORBIDDEN,
                    "Reviewers cannot review their own releases");
        }
        ReviewDecisionType decision;
        try {
            decision = ReviewDecisionType.valueOf(request.decision().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "decision must be APPROVE, REJECT or REQUEST_CHANGES");
        }
        switch (decision) {
            case APPROVE -> approve(reviewerUserId, review, release, listing, request.notes());
            case REJECT -> {
                ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.REJECTED);
                release.status = ReleaseStatus.REJECTED;
                review.status = "REJECTED";
                enqueue(StoreEventPayloads.RELEASE_REJECTED, release.id, toJson(
                        new StoreEventPayloads.ReleaseRejected(
                                listing.coordinate().withVersion(release.version).toString(),
                                release.id.toString(), "review.rejected", List.of())));
            }
            case REQUEST_CHANGES -> {
                ReleaseStateMachine.assertTransition(release.status,
                        ReleaseStatus.CHANGES_REQUESTED);
                release.status = ReleaseStatus.CHANGES_REQUESTED;
                review.status = "CHANGES_REQUESTED";
            }
        }
        review.reviewerId = reviewerUserId;
        review.notes = request.notes();
        review.decidedAt = Instant.now();
        reviews.save(review);
        releases.save(release);
        audit.record(reviewerUserId == null ? "SERVICE" : "USER",
                reviewerUserId == null ? "store-cli" : reviewerUserId.toString(),
                "review." + decision.name().toLowerCase(), "REVIEW", review.id.toString(),
                null, release.status.name(), null);
        return review;
    }

    private void approve(UUID reviewerUserId, Review review, Release release, Listing listing,
            String notes) {
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.APPROVED);
        release.status = ReleaseStatus.APPROVED;
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.PUBLISHED);
        release.status = ReleaseStatus.PUBLISHED;
        release.publishedAt = Instant.now();

        // Platform signature over the canonical envelope (design §8.3).
        String envelopeJson = catalog.canonicalJson(catalog.buildEnvelope(listing, release));
        String signature = signing.sign(envelopeJson);
        String keyId = signing.currentKeyId();
        release.artifacts = new ArrayList<>(release.artifacts);
        for (int i = 0; i < release.artifacts.size(); i++) {
            Release.ArtifactInfo a = release.artifacts.get(i);
            release.artifacts.set(i, new Release.ArtifactInfo(a.id(), a.kind(), a.platform(),
                    a.arch(), a.filename(), a.size(), a.sha256(),
                    a.signature() == null ? signature : a.signature(),
                    a.keyId() == null ? keyId : a.keyId(), a.blobKey(), a.mimeType()));
        }
        review.status = "APPROVED";
        listing.updatedAt = Instant.now();
        listings.save(listing);
        enqueue(StoreEventPayloads.RELEASE_PUBLISHED, release.id, toJson(
                new StoreEventPayloads.ReleasePublished(
                        listing.coordinate().withVersion(release.version).toString(),
                        release.id.toString(), release.version.toString(),
                        release.channel.name().toLowerCase(), release.publishedAt.toString(),
                        envelopeJson)));
    }

    // ---- security withdrawals (design §8.1) ----

    @Transactional
    public Release yank(UUID adminUserId, UUID releaseId, String reason) {
        Release release = loadPublished(releaseId);
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.YANKED);
        release.status = ReleaseStatus.YANKED;
        releases.save(release);
        Listing listing = listings.findById(release.listingId).orElse(null);
        String coordinate = listing == null ? releaseId.toString()
                : listing.coordinate().withVersion(release.version).toString();
        enqueue(StoreEventPayloads.RELEASE_YANKED, release.id, toJson(
                new StoreEventPayloads.ReleaseYanked(coordinate, release.id.toString(), reason)));
        audit.record("USER", adminUserId == null ? "system" : adminUserId.toString(),
                "release.yank", "RELEASE", release.id.toString(), null, reason, null);
        return release;
    }

    @Transactional
    public Release quarantine(UUID adminUserId, UUID releaseId, String reason) {
        Release release = loadPublished(releaseId);
        ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.QUARANTINED);
        release.status = ReleaseStatus.QUARANTINED;
        releases.save(release);
        Listing listing = listings.findById(release.listingId).orElse(null);
        String coordinate = listing == null ? releaseId.toString()
                : listing.coordinate().withVersion(release.version).toString();
        enqueue(StoreEventPayloads.RELEASE_QUARANTINED, release.id, toJson(
                new StoreEventPayloads.ReleaseQuarantined(coordinate, release.id.toString(),
                        reason)));
        audit.record("USER", adminUserId == null ? "system" : adminUserId.toString(),
                "release.quarantine", "RELEASE", release.id.toString(), null, reason, null);
        return release;
    }

    public List<PublisherDtos.ScanFindingDto> findingsOf(Review review) {
        return review.findings.stream()
                .map(f -> new PublisherDtos.ScanFindingDto(f.severity(), f.rule(), f.message()))
                .toList();
    }

    private Release loadPublished(UUID releaseId) {
        Release release = releases.findById(releaseId).orElseThrow(
                () -> new DomainException(StoreErrorCode.RELEASE_NOT_FOUND,
                        "Release not found"));
        if (release.status != ReleaseStatus.PUBLISHED && release.status != ReleaseStatus.DEPRECATED) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Only published releases can be withdrawn (current: " + release.status + ")");
        }
        return release;
    }

    private void enqueue(String type, UUID aggregateId, String payloadJson) {
        outbox.enqueue(new OutboxRecord(UuidV7.generate(), "RELEASE", aggregateId.toString(),
                type, payloadJson, OutboxRecord.STATUS_PENDING, 0, Instant.now(), Instant.now()));
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }
}
