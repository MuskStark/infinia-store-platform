package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.ListingDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.ListingRating;
import dev.infinia.store.domain.model.ListingReport;
import dev.infinia.store.domain.port.LibraryRepositories.RatingRepository;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories.ReportRepository;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Community moderation (design §12.4): listing ratings and abuse reports.
 * Reports are resolved by platform admins from the admin console.
 */
@Service
public class ModerationService {

    private static final Set<String> REPORT_REASONS = Set.of("malware", "policy_violation",
            "spam", "misleading", "license", "other");

    private final RatingRepository ratings;
    private final ReportRepository reports;
    private final ListingRepository listings;
    private final AuditService audit;

    public ModerationService(RatingRepository ratings, ReportRepository reports,
            ListingRepository listings, AuditService audit) {
        this.ratings = ratings;
        this.reports = reports;
        this.listings = listings;
        this.audit = audit;
    }

    @Transactional
    public ListingRating rate(UUID userId, Listing listing, Integer stars, String comment) {
        if (stars == null || stars < 1 || stars > 5) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "stars must be between 1 and 5");
        }
        if (comment != null && comment.length() > 2000) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "comment must be at most 2000 characters");
        }
        ListingRating existing = ratings.findByUserAndListing(userId, listing.id).orElse(null);
        Instant now = Instant.now();
        ListingRating rating = new ListingRating(
                existing == null ? UuidV7.generate() : existing.id(), listing.id, userId, stars,
                comment, existing == null ? now : existing.createdAt(), now);
        ratings.upsert(rating);
        return rating;
    }

    public ListingDtos.RatingsPageDto ratingsOf(Listing listing) {
        List<ListingDtos.RatingDto> items = ratings.findByListing(listing.id, 50).stream()
                .map(r -> new ListingDtos.RatingDto(r.id().toString(), r.userId().toString(),
                        r.stars(), r.comment(), r.updatedAt().toString()))
                .toList();
        var summary = ratings.summarize(listing.id);
        return new ListingDtos.RatingsPageDto(
                new ListingDtos.RatingSummaryDto(summary.count(), summary.average()), items);
    }

    @Transactional
    public ListingReport report(UUID reporterId, Listing listing, String reason, String details) {
        if (reason == null || !REPORT_REASONS.contains(reason)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "reason must be one of " + REPORT_REASONS);
        }
        if (details != null && details.length() > 2000) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "details must be at most 2000 characters");
        }
        if (reports.existsOpenByReporterAndListing(reporterId, listing.id)) {
            throw new DomainException(StoreErrorCode.IDEMPOTENCY_CONFLICT,
                    "You already have an open report for this listing");
        }
        ListingReport record = new ListingReport(UuidV7.generate(), listing.id, reporterId,
                reason, details, ListingReport.STATUS_OPEN, null, null, Instant.now(), null);
        reports.save(record);
        audit.record("USER", reporterId.toString(), "listing.report", "LISTING",
                listing.id.toString(), null, reason, null);
        return record;
    }

    public List<ListingReport> reportQueue(String status) {
        return reports.findByStatus(status == null || status.isBlank()
                ? ListingReport.STATUS_OPEN : status.toUpperCase(), 100);
    }

    @Transactional
    public ListingReport resolveReport(UUID adminUserId, UUID reportId, String resolution,
            String note) {
        ListingReport report = reports.findById(reportId)
                .orElseThrow(() -> new DomainException(StoreErrorCode.REPORT_NOT_FOUND,
                        "Report not found"));
        if (!"ACTIONED".equals(resolution) && !"DISMISSED".equals(resolution)) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "resolution must be ACTIONED or DISMISSED");
        }
        if (!ListingReport.STATUS_OPEN.equals(report.status())) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Report is already resolved (status: " + report.status() + ")");
        }
        ListingReport resolved = new ListingReport(report.id(), report.listingId(),
                report.reporterId(), report.reason(), report.details(), resolution, note,
                adminUserId, report.createdAt(), Instant.now());
        reports.save(resolved);
        audit.record("USER", adminUserId.toString(), "report." + resolution.toLowerCase(),
                "LISTING_REPORT", report.id().toString(), ListingReport.STATUS_OPEN, resolution,
                null);
        return resolved;
    }

    public Listing listingOfReport(ListingReport report) {
        return listings.findById(report.listingId()).orElse(null);
    }
}
