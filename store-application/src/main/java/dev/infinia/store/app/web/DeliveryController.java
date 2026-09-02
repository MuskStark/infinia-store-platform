package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.PublisherService;
import dev.infinia.store.app.service.TicketService;
import dev.infinia.store.contract.api.DeliveryDtos;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.BlobStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Download tickets and blob transfer (design §10.2). Upload/download tickets are
 * HMAC-signed, short-lived and purpose-limited — the local equivalent of S3
 * presigned URLs, so large artifacts never pass through JSON endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class DeliveryController {

    private final CatalogService catalog;
    private final PublisherService publisher;
    private final dev.infinia.store.domain.port.ReleaseRepository releases;
    private final TicketService tickets;
    private final BlobStorage blobs;
    private final StoreProperties properties;
    private final dev.infinia.store.app.upstream.UpstreamArtifactService upstreamArtifacts;

    public DeliveryController(CatalogService catalog, PublisherService publisher,
            dev.infinia.store.domain.port.ReleaseRepository releases,
            TicketService tickets, BlobStorage blobs, StoreProperties properties,
            dev.infinia.store.app.upstream.UpstreamArtifactService upstreamArtifacts) {
        this.upstreamArtifacts = upstreamArtifacts;
        this.catalog = catalog;
        this.publisher = publisher;
        this.releases = releases;
        this.tickets = tickets;
        this.blobs = blobs;
        this.properties = properties;
    }

    @PostMapping("/releases/{releaseId}/download-ticket")
    public DeliveryDtos.DownloadTicketDto ticket(@PathVariable UUID releaseId,
            @RequestParam(required = false) String artifactId,
            @RequestParam(required = false) String os,
            @RequestParam(required = false) String arch) {
        Release release = catalog.releaseOrThrow(releaseId);
        if (!release.installable()) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Release is not installable (status " + release.status + ")");
        }
        Release.ArtifactInfo artifact = catalog.pickArtifact(release, artifactId, os, arch);
        Instant expiresAt = Instant.now().plusSeconds(properties.downloadTicketTtlSeconds());
        String signature = tickets.sign("download", artifact.blobKey(), expiresAt);
        // Server-relative URL: clients resolve it against the API host (design §10.2).
        String url = "/api/v1/blobs/" + artifact.blobKey() + "?"
                + TicketService.encodeTicketParams("download", artifact.blobKey(), expiresAt,
                        signature);
        boolean live = artifact.blobKey() != null && artifact.blobKey().startsWith("upstream/");
        return new DeliveryDtos.DownloadTicketDto(release.id.toString(),
                artifact.id() == null ? null : artifact.id().toString(), url,
                expiresAt.toString(), live ? null : artifact.sha256(),
                live ? null : artifact.signature(), live ? null : artifact.keyId(),
                live ? 0 : artifact.size());
    }

    /** Ticketed blob download; anonymous by design (ticket IS the authorization). */
    @GetMapping("/blobs/{*blobKey}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String blobKey,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig) {
        String key = blobKey.startsWith("/") ? blobKey.substring(1) : blobKey;
        if (!"download".equals(purpose) || exp == null
                || !tickets.verify("download", key, Instant.ofEpochSecond(exp), sig)) {
            throw new DomainException(StoreErrorCode.TICKET_INVALID,
                    "Download ticket is invalid or expired");
        }
        // Pass-through upstream artifacts (aggregation plan §5.2): rebuild from the
        // upstream, verify against the recorded content digest, stream — no blob.
        if (key.startsWith("upstream/")) {
            java.util.UUID upstreamItemId;
            try {
                upstreamItemId = java.util.UUID.fromString(
                        key.substring("upstream/".length()));
            } catch (IllegalArgumentException e) {
                throw new DomainException(StoreErrorCode.TICKET_INVALID,
                        "Malformed upstream artifact key");
            }
            dev.infinia.store.app.upstream.UpstreamArtifactService.PreparedArtifact prepared;
            try {
                // The rebuilt package embeds the release version — resolve the
                // owning release so pass-through bytes match the recorded sha.
                Release owner = releases.findByArtifactBlobKey(key).orElse(null);
                prepared = upstreamArtifacts.prepare(upstreamItemId,
                        owner == null ? null : owner.version.toString());
            } catch (RuntimeException e) {
                String message = String.valueOf(e.getMessage());
                if (message.contains("changed since sync")) {
                    throw new DomainException(StoreErrorCode.UPSTREAM_DRIFTED, message);
                }
                if (e instanceof dev.infinia.store.app.upstream.UpstreamArtifactService
                        .UpstreamPayloadRejectedException) {
                    throw new DomainException(StoreErrorCode.SCAN_FAILED, message);
                }
                throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                        "Upstream pass-through failed: " + message);
            } catch (IOException | InterruptedException e) {
                throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                        "Upstream fetch failed: " + e.getMessage());
            }
            byte[] digest = java.util.HexFormat.of().parseHex(prepared.sha256());
            StreamingResponseBody body = out -> {
                try (prepared; InputStream in = java.nio.file.Files
                        .newInputStream(prepared.file())) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(prepared.size()))
                    .header("Digest", "sha-256=" + Base64.getEncoder().encodeToString(digest))
                    .header("X-Checksum-SHA256", prepared.sha256())
                    .header("Cache-Control", "no-store")
                    .body(body);
        }
        InputStream in = blobs.open(key);
        StreamingResponseBody body = out -> {
            try (in) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(body);
    }

    /**
     * Deterministic checksums.txt manifest for a release (design §8.3: APP releases
     * must ship checksums alongside the signed binaries). Derived from the signed
     * artifact metadata — one {@code sha256  filename} line per binary artifact in
     * sha256sum format, so {@code sha256sum -c checksums.txt} works verbatim.
     */
    @GetMapping(value = "/releases/{releaseId}/checksums.txt", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> checksums(@PathVariable UUID releaseId) {
        Release release = catalog.releaseOrThrow(releaseId);
        if (!release.installable()) {
            throw new DomainException(StoreErrorCode.INVALID_STATE_TRANSITION,
                    "Release is not installable (status " + release.status + ")");
        }
        StringBuilder manifest = new StringBuilder();
        release.artifacts.stream()
                .filter(a -> a.kind() == dev.infinia.store.contract.type.ArtifactKind.INSTALLER
                        || a.kind() == dev.infinia.store.contract.type.ArtifactKind.PORTABLE
                        || a.kind() == dev.infinia.store.contract.type.ArtifactKind.PACKAGE)
                .filter(a -> a.sha256() != null && a.filename() != null)
                .sorted(java.util.Comparator.comparing(Release.ArtifactInfo::filename))
                .forEach(a -> manifest.append(a.sha256()).append("  ")
                        .append(a.filename()).append('\n'));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(manifest.toString());
    }

    /** Presigned-URL equivalent for uploads (design §8.2 step 1). */
    @PutMapping("/blobs/uploads/{uploadId}")
    public ResponseEntity<Void> upload(@PathVariable UUID uploadId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig,
            HttpServletRequest request) throws java.io.IOException {
        if (!"upload".equals(purpose) || exp == null
                || !tickets.verify("upload", uploadId.toString(),
                        Instant.ofEpochSecond(exp), sig)) {
            throw new DomainException(StoreErrorCode.TICKET_INVALID,
                    "Upload ticket is invalid or expired");
        }
        publisher.completeUpload(uploadId, request.getInputStream());
        return ResponseEntity.noContent().build();
    }
}
