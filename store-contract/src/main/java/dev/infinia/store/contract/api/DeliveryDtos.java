package dev.infinia.store.contract.api;

/** DTOs for artifact delivery (design §8.4, §10.2). */
public final class DeliveryDtos {

    private DeliveryDtos() {}

    /**
     * Short-lived, purpose-limited CDN download ticket (design §10.2).
     *
     * <p>The historic app-update feed DTOs were removed with the RESERVED
     * marking of {@code GET /api/v1/updates/app} (audit 3.5) — no shipped
     * client consumed that shape; the live update surfaces are the
     * electron-updater deb feed and the compat GitHub-releases mirror.
     */
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
}
