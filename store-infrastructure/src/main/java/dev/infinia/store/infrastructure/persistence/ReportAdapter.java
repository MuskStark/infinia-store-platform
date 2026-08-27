package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.ListingReport;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.ListingReportEntity;
import dev.infinia.store.infrastructure.persistence.repository.ListingReportJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReportAdapter implements PublishingRepositories.ReportRepository {

    private final ListingReportJpaRepository jpa;

    public ReportAdapter(ListingReportJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ListingReport report) {
        ListingReportEntity e = jpa.findById(report.id()).orElseGet(ListingReportEntity::new);
        e.id = report.id();
        e.listingId = report.listingId();
        e.reporterId = report.reporterId();
        e.reason = report.reason();
        e.details = report.details();
        e.status = report.status();
        e.resolutionNote = report.resolutionNote();
        e.resolvedBy = report.resolvedBy();
        e.createdAt = report.createdAt();
        e.resolvedAt = report.resolvedAt();
        jpa.save(e);
    }

    @Override
    public Optional<ListingReport> findById(UUID id) {
        return jpa.findById(id).map(ReportAdapter::toDomain);
    }

    @Override
    public List<ListingReport> findByStatus(String status, int limit) {
        return jpa.findTop100ByStatusOrderByCreatedAtDesc(status).stream()
                .limit(limit)
                .map(ReportAdapter::toDomain)
                .toList();
    }

    @Override
    public boolean existsOpenByReporterAndListing(UUID reporterId, UUID listingId) {
        return jpa.existsByReporterIdAndListingIdAndStatus(reporterId, listingId,
                ListingReport.STATUS_OPEN);
    }

    private static ListingReport toDomain(ListingReportEntity e) {
        return new ListingReport(e.id, e.listingId, e.reporterId, e.reason, e.details, e.status,
                e.resolutionNote, e.resolvedBy, e.createdAt, e.resolvedAt);
    }
}
