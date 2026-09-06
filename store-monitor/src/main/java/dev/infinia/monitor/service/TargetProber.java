package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Blackbox probes over the store's public base URL — the same path a user's
 * browser takes. All four targets answering: operational. The health endpoint
 * failing (it aggregates the store's database): major outage. Any other target
 * failing: degraded. The probe never throws; unreachable is an answer, not an
 * error.
 */
@Component
public class TargetProber {

    private static final Logger log = LoggerFactory.getLogger(TargetProber.class);

    /** Derived from the single configured base URL — no per-path config. */
    private static final List<String> PROBE_PATHS = List.of(
            "/",
            "/actuator/health",
            "/api/v1/status",
            "/.well-known/openid-configuration");

    private final MonitorProperties properties;
    private final HttpClient http;

    public TargetProber(MonitorProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.probeTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String probe() {
        String base = properties.targetBaseUrl().toString().replaceAll("/+$", "");
        boolean healthFailure = false;
        int failures = 0;
        for (String path : PROBE_PATHS) {
            if (!succeeds(URI.create(base + path))) {
                failures++;
                healthFailure |= path.equals("/actuator/health");
            }
        }
        if (healthFailure) {
            return Indicators.MAJOR_OUTAGE;
        }
        return failures > 0 ? Indicators.DEGRADED : Indicators.OPERATIONAL;
    }

    private boolean succeeds(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.probeTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<Void> response =
                    http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.debug("Probe {} failed: {}", uri, e.getMessage());
            return false;
        }
    }
}
