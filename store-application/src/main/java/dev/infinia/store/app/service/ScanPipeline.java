package dev.infinia.store.app.service;

import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.service.ReleaseStateMachine;
import dev.infinia.store.scanner.PackageScanner;
import dev.infinia.store.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Async scan stage of the publishing pipeline (design §8.2). Runs in its own executor
 * so the deployable can move it to dedicated scanner workers later; blocking findings
 * auto-reject, clean packages move the release to human review.
 */
@Component
public class ScanPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScanPipeline.class);

    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final PublishingRepositories.ReviewRepository reviews;
    private final BlobStorage blobs;
    private final PackageScanner scanner = new PackageScanner();

    public ScanPipeline(ReleaseRepository releases, ListingRepository listings,
            PublishingRepositories.ReviewRepository reviews, BlobStorage blobs) {
        this.releases = releases;
        this.listings = listings;
        this.reviews = reviews;
        this.blobs = blobs;
    }

    @Async("scanExecutor")
    @Transactional
    public void runScan(UUID releaseId, UUID reviewId) {
        // Wait briefly for the submitting transaction to commit before flipping state.
        Release release = null;
        Review review = null;
        for (int attempt = 0; attempt < 100 && (release == null || review == null); attempt++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            release = releases.findById(releaseId).orElse(null);
            review = reviews.findById(reviewId).orElse(null);
        }
        if (release == null || review == null) {
            log.error("Scan target missing after retries: release={}", releaseId);
            return;
        }
        Listing listing = listings.findById(release.listingId).orElse(null);
        Release.ArtifactInfo artifact = release.artifacts.stream()
                .filter(a -> a.kind() == ArtifactKind.PACKAGE)
                .findFirst()
                .orElse(null);
        ScanResult result;
        if (artifact == null) {
            result = new ScanResult();
            result.error("scanner.no-package", "No PACKAGE artifact attached");
        } else {
            try (InputStream in = blobs.open(artifact.blobKey())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                result = scanner.scan(listing == null ? "PLUGIN" : listing.type.name(),
                        release.version.toString(), out.toByteArray());
            } catch (IOException e) {
                result = new ScanResult();
                result.error("scanner.io", "Package could not be read: " + e.getMessage());
            }
        }
        review.findings = result.findings.stream()
                .map(f -> new Review.Finding(f.severity(), f.rule(), f.message()))
                .toList();
        if (result.hasBlockingFindings()) {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.REJECTED);
            release.status = ReleaseStatus.REJECTED;
            review.status = "REJECTED";
        } else {
            ReleaseStateMachine.assertTransition(release.status, ReleaseStatus.IN_REVIEW);
            release.status = ReleaseStatus.IN_REVIEW;
            // The package is the source of truth for permissions (design §8.2 step 6):
            // what the reviewer approves, the resolver surfaces and the host confirms
            // must match the shipped manifest, not the publisher's initial claim.
            if (!result.extractedPermissions.isEmpty()) {
                release.permissions = result.extractedPermissions.stream()
                        .map(p -> new Release.PermissionDecl(
                                String.valueOf(p.get("permissionId")),
                                "plugin", true, null))
                        .toList();
            }
        }
        releases.save(release);
        reviews.save(review);
        log.info("Scan finished for release {}: blocking={}, findings={}", releaseId,
                result.hasBlockingFindings(), result.findings.size());
    }
}
