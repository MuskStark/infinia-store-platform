package dev.infinia.store.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-level configuration (design §5.2, §8.3, §13.2). */
@ConfigurationProperties(prefix = "store")
public record StoreProperties(
        String baseUrl,
        String blobDir,
        String keyDir,
        String ticketSecret,
        String rolloutSecret,
        long maxUploadBytes,
        long downloadTicketTtlSeconds,
        long uploadTicketTtlSeconds,
        java.util.List<String> allowedOrigins,
        String webRedirectUri,
        String cliClientId,
        String cliClientSecret) {

    public StoreProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl;
        blobDir = blobDir == null || blobDir.isBlank() ? "data/blobs" : blobDir;
        keyDir = keyDir == null || keyDir.isBlank() ? "data/keys" : keyDir;
        ticketSecret = ticketSecret == null || ticketSecret.isBlank()
                ? "dev-only-ticket-secret-change-me" : ticketSecret;
        rolloutSecret = rolloutSecret == null || rolloutSecret.isBlank()
                ? "dev-only-rollout-secret-change-me" : rolloutSecret;
        maxUploadBytes = maxUploadBytes <= 0 ? 100L * 1024 * 1024 : maxUploadBytes;
        downloadTicketTtlSeconds = downloadTicketTtlSeconds <= 0 ? 300 : downloadTicketTtlSeconds;
        uploadTicketTtlSeconds = uploadTicketTtlSeconds <= 0 ? 900 : uploadTicketTtlSeconds;
        allowedOrigins = allowedOrigins == null ? java.util.List.of() : allowedOrigins;
        webRedirectUri = webRedirectUri == null || webRedirectUri.isBlank()
                ? "http://localhost:8089/callback" : webRedirectUri;
        cliClientId = cliClientId == null || cliClientId.isBlank() ? "store-cli" : cliClientId;
        cliClientSecret = cliClientSecret == null || cliClientSecret.isBlank()
                ? "dev-only-cli-secret" : cliClientSecret;
    }
}
