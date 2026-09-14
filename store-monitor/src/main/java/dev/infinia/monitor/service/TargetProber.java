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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blackbox probes over the store's public base URL — the same path a user's
 * browser takes. Paths split in two classes: <b>service paths</b> ({@code /}
 * and the status API — what users actually consume) and <b>diagnostic paths</b>
 * ({@code /actuator/health}, the OIDC discovery document). A network error, a
 * 5xx anywhere, or any non-2xx on a service path is a failure: the health
 * endpoint failing (it aggregates the store's database) escalates to major
 * outage, any other failure is partial. A 4xx on a <i>diagnostic</i> path is
 * inconclusive instead: a WAF rule or a renamed endpoint must not be read as a
 * platform outage — that is a monitor configuration problem, flagged via
 * {@link Outcome#diagnosticsInconclusive()} and logged, not painted red. The
 * prober never throws; unreachable is an answer, not an error.
 */
@Component
public class TargetProber {

    private static final Logger log = LoggerFactory.getLogger(TargetProber.class);

    /** Derived from the single configured base URL — no per-path config. */
    private static final List<String> SERVICE_PATHS = List.of("/", "/api/v1/status");
    private static final List<String> DIAGNOSTIC_PATHS =
            List.of("/actuator/health", "/.well-known/openid-configuration");

    /** One probe round's verdict plus the per-path evidence behind it. */
    public record Outcome(String indicator, List<PathProbe> probes, boolean diagnosticsInconclusive) {
        public record PathProbe(String path, int status, boolean networkError) {}
    }

    private final MonitorProperties properties;
    private final HttpClient http;
    /** Logs the config warning once per flip, not once per 5-second probe. */
    private final AtomicBoolean warnedInconclusive = new AtomicBoolean(false);

    public TargetProber(MonitorProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.probeTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Outcome probe() {
        String base = properties.targetBaseUrl().toString().replaceAll("/+$", "");
        // Parallel probes: four sequential 8-second timeouts would stretch one
        // round to half a minute against a hanging store — the timeout must
        // bound the round, not multiply into it.
        List<java.util.concurrent.CompletableFuture<Outcome.PathProbe>> futures =
                new ArrayList<>(SERVICE_PATHS.size() + DIAGNOSTIC_PATHS.size());
        for (String path : SERVICE_PATHS) {
            futures.add(probePath(base, path));
        }
        for (String path : DIAGNOSTIC_PATHS) {
            futures.add(probePath(base, path));
        }
        List<Outcome.PathProbe> probes = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .toList();
        boolean healthFailure = false;
        boolean otherFailure = false;
        boolean inconclusive = false;
        for (Outcome.PathProbe probe : probes) {
            if (SERVICE_PATHS.contains(probe.path())) {
                // What users consume: anything but 2xx (or the store vanishing) is real.
                otherFailure |= probe.networkError() || probe.status() < 200 || probe.status() >= 300;
            } else if (failed(probe)) {
                healthFailure |= probe.path().equals("/actuator/health");
                otherFailure |= !probe.path().equals("/actuator/health");
            } else {
                // 403/404 on a diagnostic path: the store may be fine while the
                // monitor's derived URL is wrong or blocked (WAF) — inconclusive.
                inconclusive |= probe.status() >= 400 && probe.status() < 500;
            }
        }
        if (inconclusive && warnedInconclusive.compareAndSet(false, true)) {
            log.warn("Diagnostic probe path answered 4xx — check the monitor's target URL "
                    + "and WAF rules; this is NOT counted as a store outage");
        } else if (!inconclusive) {
            warnedInconclusive.set(false);
        }
        String indicator;
        if (healthFailure) {
            indicator = Indicators.MAJOR_OUTAGE;
        } else {
            indicator = otherFailure ? Indicators.PARTIAL_OUTAGE : Indicators.OPERATIONAL;
        }
        return new Outcome(indicator, probes, inconclusive);
    }

    private static boolean failed(Outcome.PathProbe probe) {
        return probe.networkError() || probe.status() >= 500;
    }

    private java.util.concurrent.CompletableFuture<Outcome.PathProbe> probePath(
            String base, String path) {
        URI uri = URI.create(base + path);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.probeTimeoutMs()))
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return java.util.concurrent.CompletableFuture
                    .completedFuture(new Outcome.PathProbe(path, 0, true));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, error) -> {
                    if (error != null) {
                        log.debug("Probe {} failed: {}", uri, error.getMessage());
                        return new Outcome.PathProbe(path, 0, true);
                    }
                    return new Outcome.PathProbe(path, response.statusCode(), false);
                });
    }
}
