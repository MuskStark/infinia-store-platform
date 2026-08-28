package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upstream_item")
public class UpstreamItemEntity {
    @Id
    public UUID id;
    @Column(name = "source_id", nullable = false)
    public UUID sourceId;
    @Column(name = "external_id", nullable = false, length = 512)
    public String externalId;
    @Column(name = "listing_id")
    public UUID listingId;
    @Column(name = "source_url", length = 1024)
    public String sourceUrl;
    @Column(name = "source_path", length = 512)
    public String sourcePath;
    @Column(length = 128)
    public String ref;
    @Column(name = "commit_sha", length = 64)
    public String commitSha;
    @Column(name = "upstream_version", length = 64)
    public String upstreamVersion;
    @Column(name = "content_sha256", nullable = false, length = 64)
    public String contentSha256;
    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt;
    @Column(name = "removed_at")
    public Instant removedAt;
}
