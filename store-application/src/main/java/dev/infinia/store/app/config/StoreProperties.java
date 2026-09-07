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
        String remoteDatasourceFile,
        Monitoring monitoring) {

    public StoreProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl;
        // Absolute defaults (working-directory independent): LocalFsBlobStorage
        // enforces blob-dir absoluteness and the check below enforces key-dir's.
        // S3 storage ignores blob-dir entirely.
        blobDir = blobDir == null || blobDir.isBlank()
                ? System.getProperty("user.home") + "/.infinia-store/blobs" : blobDir;
        keyDir = keyDir == null || keyDir.isBlank()
                ? System.getProperty("user.home") + "/.infinia-store/keys" : keyDir;
        // blob-dir absoluteness is enforced by LocalFsBlobStorage (the only backend
        // that consumes it — S3 storage ignores it); key-dir stays local-disk state.
        if (!java.nio.file.Path.of(keyDir).isAbsolute()) {
            throw new IllegalStateException("store.key-dir must be an absolute path "
                    + "(working-directory-independent); got key-dir=" + keyDir);
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
        remoteDatasourceFile = RemoteDataSourceOverride.overridePath(remoteDatasourceFile)
                .toString();
        monitoring = monitoring == null
                ? new Monitoring(null, null, null, null, null, null, null, null, null, null)
                : monitoring;
    }

    /** Product sign-in page derived from the configured Store Web callback origin. */
    public String webSignInUri() {
        return java.net.URI.create(webRedirectUri).resolve("/signin").toString();
    }

    /**
     * Comprehensive-monitoring thresholds for the in-process probes the status
     * page runs (host disk/memory, JVM heap, DB pool, HTTP quality). Absent
     * values fall back to their defaults, so operators only override what they
     * care about; an explicit zero stays zero (e.g. "never alarm").
     */
    public record Monitoring(
            Integer diskWarnFreePercent,
            Integer diskCriticalFreePercent,
            Integer memoryWarnFreePercent,
            Integer memoryCriticalFreePercent,
            Integer heapWarnUsedPercent,
            Integer poolWarnActivePercent,
            Integer poolAwaitingOutageThreads,
            Double http5xxWarnPercent,
            Double http5xxCriticalPercent,
            Long httpP95WarnMillis) {

        public Monitoring {
            // Heap pressure deliberately never escalates past degraded: a full
            // heap often self-heals on the next GC cycle, and a false outage
            // tier would repaint the whole page for a transient spike.
            if (diskWarnFreePercent == null) diskWarnFreePercent = 15;
            if (diskCriticalFreePercent == null) diskCriticalFreePercent = 5;
            if (memoryWarnFreePercent == null) memoryWarnFreePercent = 10;
            if (memoryCriticalFreePercent == null) memoryCriticalFreePercent = 3;
            if (heapWarnUsedPercent == null) heapWarnUsedPercent = 85;
            if (poolWarnActivePercent == null) poolWarnActivePercent = 80;
            if (poolAwaitingOutageThreads == null) poolAwaitingOutageThreads = 5;
            if (http5xxWarnPercent == null) http5xxWarnPercent = 1.0;
            if (http5xxCriticalPercent == null) http5xxCriticalPercent = 5.0;
            if (httpP95WarnMillis == null) httpP95WarnMillis = 1500L;
        }
    }
}
