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
@Table(name = "release")
public class ReleaseEntity {
    @Id
    public UUID id;
    @Column(name = "listing_id", nullable = false)
    public UUID listingId;
    @Column(name = "version", nullable = false)
    public String version;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "channel", nullable = false)
    public String channel;
    @Column(name = "published_at")
    public Instant publishedAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "requires_host")
    public String requiresHost;
    @Column(name = "license")
    public String license;
    @Column(name = "source_url")
    public String sourceUrl;
    @Column(name = "changelog_md")
    public String changelogMarkdown;
    @Column(name = "rollout_percent", nullable = false)
    public int rolloutPercent;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "release_artifact", joinColumns = @JoinColumn(name = "release_id"))
    public List<ArtifactEmb> artifacts = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "release_dependency", joinColumns = @JoinColumn(name = "release_id"))
    public List<DependencyEmb> dependencies = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "release_permission", joinColumns = @JoinColumn(name = "release_id"))
    public List<PermissionEmb> permissions = new ArrayList<>();

    @Embeddable
    public static record ArtifactEmb(String kind, String platform, String arch, String filename,
            @Column(name = "size_bytes") long size, String sha256, String signature, String keyId,
            @Column(name = "blob_key") String blobKey,
            @Column(name = "mime_type") String mimeType) {
    }

    @Embeddable
    public static record DependencyEmb(String coordinate,
            @Column(name = "range_expr") String range, boolean optional) {
    }

    @Embeddable
    public static record PermissionEmb(String permissionId, String scope, boolean required,
            String reason) {
    }
}
