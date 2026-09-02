package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for artifact delivery and the signed app update feed (design §8.4). */
public final class DeliveryDtos {

    private DeliveryDtos() {}

    /** Short-lived, purpose-limited CDN download ticket (design §10.2). */
    public record DownloadTicketDto(
            String releaseId,
            String artifactId,
            String url,
            String expiresAt,
            String sha256,
            String signature,
            String keyId,
            long size) {
    }

    public record AppUpdateArtifactDto(
            String url,
            String filename,
            String sha256,
            String signature,
            String keyId,
            long size,
            String platform,
            String arch,
            String kind,
            String variant,
            String mimeType) {
    }

    /**
     * Response of {@code GET /api/v1/updates/app}; field-compatible with the FengYu
     * host {@code UpdateInfo} model (design §8.4).
     */
    public record AppUpdateDto(
            String latestVersion,
            boolean mandatory,
            int rollout,
            String releaseNotes,
            List<AppUpdateArtifactDto> artifacts,
            String sha256,
            String signature,
            String keyId,
            String publishedAt,
            String minimumSupportedVersion,
            String channel) {
    }
}
