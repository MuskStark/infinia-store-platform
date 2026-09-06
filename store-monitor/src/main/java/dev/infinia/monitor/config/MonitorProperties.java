package dev.infinia.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Monitor configuration. The one root setting is {@code monitor.target-base-url}:
 * the store's public address — every probe target and the mirrored status API
 * derive from it, so re-pointing the monitor at a moved store is a single
 * environment variable ({@code MONITOR_TARGET_BASE_URL}) and a restart.
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(
        URI targetBaseUrl,
        Long pollIntervalMs,
        Long probeTimeoutMs,
        String mirrorDir,
        String alertWebhook,
        Integer alertThrottleMinutes,
        Integer staleAfterMs,
        Integer historyDays) {

    public MonitorProperties {
        if (targetBaseUrl == null) {
            targetBaseUrl = URI.create("http://localhost:8080");
        }
        String scheme = targetBaseUrl.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))
                || targetBaseUrl.getHost() == null) {
            throw new IllegalStateException("monitor.target-base-url must be an absolute "
                    + "http(s) URL pointing at the store, got: " + targetBaseUrl);
        }
        if (pollIntervalMs == null || pollIntervalMs <= 0) {
            pollIntervalMs = 60_000L;
        }
        if (probeTimeoutMs == null || probeTimeoutMs <= 0) {
            probeTimeoutMs = 8_000L;
        }
        // A relative storage path silently splits data when the working directory
        // changes between launches — refuse to boot instead (StoreProperties style).
        if (mirrorDir == null || mirrorDir.isBlank()) {
            mirrorDir = System.getProperty("user.home") + "/.infinia-monitor";
        }
        if (!java.nio.file.Path.of(mirrorDir).isAbsolute()) {
            throw new IllegalStateException("monitor.mirror-dir must be an absolute path, got: "
                    + mirrorDir);
        }
        if (alertWebhook == null) {
            alertWebhook = "";
        }
        if (alertThrottleMinutes == null || alertThrottleMinutes <= 0) {
            alertThrottleMinutes = 15;
        }
        if (staleAfterMs == null || staleAfterMs <= 0) {
            staleAfterMs = 180_000;
        }
        if (historyDays == null || historyDays <= 0) {
            historyDays = 90;
        }
    }
}
