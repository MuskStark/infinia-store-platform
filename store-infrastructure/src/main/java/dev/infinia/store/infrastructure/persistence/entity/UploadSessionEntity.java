package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_session")
public class UploadSessionEntity {
    @Id
    public UUID id;
    @Column(name = "release_id", nullable = false)
    public UUID releaseId;
    @Column(name = "filename", nullable = false)
    public String filename;
    @Column(name = "kind", nullable = false)
    public String kind;
    @Column(name = "platform", nullable = false)
    public String platform;
    @Column(name = "arch", nullable = false)
    public String arch;
    @Column(name = "declared_size", nullable = false)
    public long declaredSize;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "blob_key")
    public String blobKey;
    @Column(name = "sha256")
    public String sha256;
    @Column(name = "mime_type")
    public String mimeType;
}
