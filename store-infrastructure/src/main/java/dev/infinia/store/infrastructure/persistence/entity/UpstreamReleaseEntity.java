package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upstream_release")
public class UpstreamReleaseEntity {
    @Id
    public UUID id;
    @Column(name = "upstream_item_id", nullable = false)
    public UUID upstreamItemId;
    @Column(name = "listing_release_id")
    public UUID listingReleaseId;
    @Column(name = "source_commit_sha", length = 64)
    public String sourceCommitSha;
    @Column(name = "source_version", length = 64)
    public String sourceVersion;
    @Column(name = "normalized_sha256", nullable = false, length = 64)
    public String normalizedSha256;
    @Column(name = "sync_run_id")
    public UUID syncRunId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
