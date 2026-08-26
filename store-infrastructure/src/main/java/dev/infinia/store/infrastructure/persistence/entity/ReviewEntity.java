package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "review")
public class ReviewEntity {
    @Id
    public UUID id;
    @Column(name = "release_id", nullable = false)
    public UUID releaseId;
    @Column(name = "listing_id", nullable = false)
    public UUID listingId;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "reviewer_id")
    public UUID reviewerId;
    @Column(name = "notes")
    public String notes;
    @Column(name = "submitted_at")
    public Instant submittedAt;
    @Column(name = "decided_at")
    public Instant decidedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "review_finding", joinColumns = @JoinColumn(name = "review_id"))
    public List<FindingEmb> findings = new ArrayList<>();

    @Embeddable
    public static record FindingEmb(String severity, String rule, String message) {
    }
}
