package dev.infinia.store.app.service;

import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Reconciles releases wedged in SCANNING (audit P2-6): a scan worker crash before
 * its fail-closed cleanup ran, an executor queue that swallowed the task, or a
 * process restart mid-scan used to leave the release stuck forever — submit is
 * rejected and reviewers never see it. The watchdog re-enqueues such scans; the
 * pipeline itself stays idempotent (a release that already moved on is skipped).
 */
@Component
public class ScanWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ScanWatchdog.class);

    /** Grace period before a SCANNING release is considered wedged. */
    static final Duration STUCK_THRESHOLD = Duration.ofMinutes(10);

    private final ReleaseRepository releases;
    private final PublishingRepositories.ReviewRepository reviews;
    private final ScanPipeline scanPipeline;

    public ScanWatchdog(ReleaseRepository releases,
            PublishingRepositories.ReviewRepository reviews, ScanPipeline scanPipeline) {
        this.releases = releases;
        this.reviews = reviews;
        this.scanPipeline = scanPipeline;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void reconcileStuckScans() {
        Instant cutoff = Instant.now().minus(STUCK_THRESHOLD);
        for (Release release : releases.findByStatus(ReleaseStatus.SCANNING)) {
            if (release.createdAt != null && release.createdAt.isAfter(cutoff)) {
                continue; // young enough that the scan may still be legitimately running
            }
            UUID reviewId = reviews.findLatestByReleaseId(release.id)
                    .map(r -> r.id)
                    .orElse(null);
            if (reviewId == null) {
                log.error("Release {} is SCANNING without a review row — cannot re-enqueue,"
                        + " needs manual cleanup", release.id);
                continue;
            }
            Review review = reviews.findById(reviewId).orElse(null);
            // submittedAt marks the scan start; a release that never left SCANNING
            // since before the cutoff is wedged regardless of review timing.
            if (review != null && review.submittedAt != null
                    && review.submittedAt.isAfter(cutoff)) {
                continue;
            }
            log.warn("Watchdog re-enqueues stuck scan: release={} (SCANNING since before"
                    + " {})", release.id, cutoff);
            scanPipeline.runScan(release.id, reviewId);
        }
    }
}
