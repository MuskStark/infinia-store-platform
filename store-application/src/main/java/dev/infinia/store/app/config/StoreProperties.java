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
        java.util.List<String> desktopRedirectUris,
        String cliClientId,
        String cliClientSecret,
        String appCoordinate,
        String appMinimumSupportedVersion,
        String remoteDatasourceFile) {

    public StoreProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl;
        blobDir = blobDir == null || blobDir.isBlank() ? "data/blobs" : blobDir;
        keyDir = keyDir == null || keyDir.isBlank() ? "data/keys" : keyDir;
        // A relative storage path silently splits data when the working directory
        // changes between IDEA/maven launches — refuse to boot instead.
        if (!java.nio.file.Path.of(blobDir).isAbsolute()
                || !java.nio.file.Path.of(keyDir).isAbsolute()) {
            throw new IllegalStateException("store.blob-dir / store.key-dir must be "
                    + "absolute paths (working-directory-independent); got blob-dir="
                    + blobDir + " key-dir=" + keyDir);
        }
        ticketSecret = ticketSecret == null || ticketSecret.isBlank()
                ? "dev-only-ticket-secret-change-me" : ticketSecret;
        rolloutSecret = rolloutSecret == null || rolloutSecret.isBlank()
                ? "dev-only-rollout-secret-change-me" : rolloutSecret;
        // Bundled-JRE desktop distributions can exceed the plugin-sized 100 MiB
        // ceiling. Uploads stream to content-addressed storage, so a 1 GiB cap
        // supports release assets without allocating the body in heap.
        maxUploadBytes = maxUploadBytes <= 0 ? 1024L * 1024 * 1024 : maxUploadBytes;
        downloadTicketTtlSeconds = downloadTicketTtlSeconds <= 0 ? 300 : downloadTicketTtlSeconds;
        uploadTicketTtlSeconds = uploadTicketTtlSeconds <= 0 ? 900 : uploadTicketTtlSeconds;
        allowedOrigins = allowedOrigins == null ? java.util.List.of() : allowedOrigins;
        // Default is same-origin: the SPA ships inside the jar, so its OAuth
        // redirect and sign-in page derive from the deployment's own base URL.
        // Split-origin development (Vite on :8089) overrides this via the dev profile.
        webRedirectUri = webRedirectUri == null || webRedirectUri.isBlank()
                ? baseUrl + "/callback" : webRedirectUri;
        desktopRedirectUris = desktopRedirectUris == null || desktopRedirectUris.isEmpty()
                ? java.util.List.of("http://127.0.0.1:24057/callback",
                        "http://localhost:24057/callback")
                : desktopRedirectUris;
        cliClientId = cliClientId == null || cliClientId.isBlank() ? "store-cli" : cliClientId;
        cliClientSecret = cliClientSecret == null || cliClientSecret.isBlank()
                ? "dev-only-cli-secret" : cliClientSecret;
        appCoordinate = appCoordinate == null || appCoordinate.isBlank()
                ? "infinia://app/official/fengyu-host" : appCoordinate;
        // Floor advertised on the update feed (design §8.4): hosts below this line
        // are told to upgrade, but the feed itself stays mandatory=false.
        if (appMinimumSupportedVersion == null || appMinimumSupportedVersion.isBlank()) {
            appMinimumSupportedVersion = "4.0.0";
        }
        remoteDatasourceFile = RemoteDataSourceOverride.overridePath(remoteDatasourceFile)
                .toString();
    }

    /** Product sign-in page derived from the configured Store Web callback origin. */
    public String webSignInUri() {
        return java.net.URI.create(webRedirectUri).resolve("/signin").toString();
    }
}
