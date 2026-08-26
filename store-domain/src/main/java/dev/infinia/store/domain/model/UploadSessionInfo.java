package dev.infinia.store.domain.model;

import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived upload session; the client PUTs bytes to a presigned URL, the platform
 * streams to blob storage and computes the SHA-256 (design §8.2 step 1-2).
 */
public class UploadSessionInfo {
    public UUID id;
    public UUID releaseId;
    public String filename;
    public ArtifactKind kind;
    public Platform platform;
    public Arch arch;
    public long declaredSize;
    /** PENDING | COMPLETED | EXPIRED */
    public String status;
    public Instant expiresAt;
    public String blobKey;
    public String sha256;
    public String mimeType;

    public UploadSessionInfo() {
    }

    public boolean expired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
