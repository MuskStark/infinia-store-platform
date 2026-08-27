package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "listing_report")
public class ListingReportEntity {
    @Id
    public UUID id;
    @Column(name = "listing_id", nullable = false)
    public UUID listingId;
    @Column(name = "reporter_id", nullable = false)
    public UUID reporterId;
    @Column(nullable = false, length = 64)
    public String reason;
    @Column(length = 2000)
    public String details;
    @Column(nullable = false)
    public String status;
    @Column(name = "resolution_note", length = 1000)
    public String resolutionNote;
    @Column(name = "resolved_by")
    public UUID resolvedBy;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "resolved_at")
    public Instant resolvedAt;
}
