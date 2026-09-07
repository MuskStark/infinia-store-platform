package dev.infinia.store.app.service;

import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.service.ReleaseStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional persistence of scan outcomes, split from the (transaction-free)
 * {@link ScanPipeline} scanning itself (audit P1-4): the outcome transaction
 * re-reads the release row right before writing, so a reviewer decision that
 * landed while the scan was running is detected — and respected — instead of
 * being overwritten by a stale snapshot. The release table's optimistic-lock
 * version covers the residual read-to-write window.
 */
@Component
public class ScanOutcomeStore {

    private static final Logger log = LoggerFactory.getLogger(ScanOutcomeStore.class);

    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final PublishingRepositories.ReviewRepository reviews;

    public ScanOutcomeStore(ReleaseRepository releases, ListingRepository listings,
            PublishingRepositories.ReviewRepository reviews) {
        this.releases = releases;
        this.listings = listings;
        this.reviews = reviews;
    }

    /**
     * Applies the scan outcome; returns false (without touching the release) when the
     * release already left SCANNING — the concurrent decision (typically a reviewer's)
     * is authoritative and merely gets the findings attached for the audit trail.
     */
    @Transactional
    public boolean applyScanOutcome(ScanPipeline.ScanWork work) {
        Release release = releases.findById(work.releaseId()).orElse(null);
        Review review = reviews.findById(work.reviewId()).orElse(null);
        if (release == null || review == null) {
            log.error("Scan outcome target missing: release={}", work.releaseId());
            return false;
        }
        if (release.status != ReleaseStatus.SCANNING) {
            log.info("Scan finished for release {} after it moved to {} — keeping the"
                    + " concurrent decision", work.releaseId(), release.status);
            if ("IN_REVIEW".equals(review.status)) {
                // No human decision yet (e.g. a watchdog outcome won the race):
                // the findings still belong on the review record.
                review.findings = work.findings();
                reviews.save(review);
            }
            return false;
        }
        review.findings = work.findings();
        if (work.blocking()) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.REJECTED);
            release.status = ReleaseStatus.REJECTED;
            review.status = "REJECTED";
        } else {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.IN_REVIEW);
            release.status = ReleaseStatus.IN_REVIEW;
            review.status = "IN_REVIEW";
            // The package is the source of truth for permissions (design §8.2 step 6):
            // what the reviewer approves, the resolver surfaces and the host confirms
            // must match the shipped manifest, not the publisher's initial claim.
            if (!work.extractedPermissions().isEmpty()) {
                release.permissions = work.extractedPermissions();
            }
        }
        releases.save(release);
        reviews.save(review);
        log.info("Scan finished for release {}: blocking={}, findings={}",
                work.releaseId(), work.blocking(), work.findings().size());
        return true;
    }

    /** Fail-closed crash handler: auto-rejects a wedged SCANNING release. */
    @Transactional
    public void markScanCrashed(UUID releaseId, UUID reviewId, String crashType) {
        boolean releaseRejected = false;
        Release release = releases.findById(releaseId).orElse(null);
        if (release != null && release.status == ReleaseStatus.SCANNING) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.REJECTED);
            release.status = ReleaseStatus.REJECTED;
            releases.save(release);
            releaseRejected = true;
        }
        if (!releaseRejected) {
            // A concurrent decision already resolved the release — its verdict stands.
            log.info("Scan crash cleanup for release {} skipped — status is {}",
                    releaseId, release == null ? "missing" : release.status);
            return;
        }
        reviews.findById(reviewId).ifPresent(review -> {
            review.findings = new java.util.ArrayList<>(List.of(
                    Review.Finding.error("scanner.error", "Scan crashed: " + crashType)));
            review.status = "REJECTED";
            reviews.save(review);
        });
    }
}
