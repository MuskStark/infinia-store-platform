package dev.infinia.store.domain.model;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A release of a listing. Published releases are immutable: blob content is
 * content-addressed and any fix must ship as a new release (design §5.3).
 */
public class Release {
    public UUID id;
    public UUID listingId;
    public SemVer version;
    public ReleaseStatus status = ReleaseStatus.DRAFT;
    public Channel channel = Channel.STABLE;
    public Instant publishedAt;
    public Instant createdAt;
    /** SemVer range of compatible host versions, e.g. {@code >=4.0.0-beta.5 <5.0.0}. */
    public String requiresHost;
    public String license;
    public String sourceUrl;
    public String changelogMarkdown;
    public int rolloutPercent = 100;
    public List<ArtifactInfo> artifacts = new ArrayList<>();
    public List<DependencyDecl> dependencies = new ArrayList<>();
    public List<PermissionDecl> permissions = new ArrayList<>();

    public record ArtifactInfo(UUID id, ArtifactKind kind, Platform platform, Arch arch,
            String variant,
            String filename, long size, String sha256, String signature, String keyId,
            String blobKey, String mimeType) {

        public ArtifactInfo {
            variant = variant == null || variant.isBlank() ? "default" : variant;
        }
    }

    public record DependencyDecl(String coordinate, String range, boolean optional) {
    }

    public record PermissionDecl(String permissionId, String scope, boolean required, String reason) {
    }

    /** Visible to new installs: PUBLISHED or DEPRECATED keep serving existing clients. */
    public boolean installable() {
        return status == ReleaseStatus.PUBLISHED;
    }
}
