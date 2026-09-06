package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alerts on component indicator transitions — the mirror's frozen internals
 * never flap, so the signals are real: a mirrored component degrading, or the
 * external component turning red when the whole store goes dark. Delivery is
 * a best-effort webhook POST; when the store is down the monitor is the only
 * alerter, so its own delivery failure is logged loudly, not swallowed.
 */
@Component
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Transport seam: tests record dispatches instead of HTTP traffic. */
    public interface Dispatcher {
        void dispatch(String webhookUrl, String json) throws Exception;
    }

    private final MonitorProperties properties;
    private final ObjectMapper mapper;
    private final Dispatcher dispatcher;
    private final Map<String, String> lastIndicators = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AlertService(MonitorProperties properties, ObjectMapper mapper) {
        this(properties, mapper, (url, json) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        });
    }

    AlertService(MonitorProperties properties, ObjectMapper mapper, Dispatcher dispatcher) {
        this.properties = properties;
        this.mapper = mapper;
        this.dispatcher = dispatcher;
    }

    /** Feeds the merged indicator map (mirrored + external) once per poll. */
    public void onIndicators(Map<String, String> indicators, Instant now) {
        for (var entry : indicators.entrySet()) {
            String component = entry.getKey();
            String indicator = entry.getValue();
            String previous = lastIndicators.put(component, indicator);
            if (previous == null || previous.equals(indicator)) {
                continue; // first sighting seeds silently; steady state is silent
            }
            Instant last = lastAlertAt.get(component);
            if (last != null && now.isBefore(
                    last.plusSeconds(properties.alertThrottleMinutes() * 60L))) {
                continue;
            }
            lastAlertAt.put(component, now);
            notify(component, previous, indicator, now);
        }
    }

    private void notify(String component, String from, String to, Instant at) {
        String webhook = properties.alertWebhook();
        if (webhook == null || webhook.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("component", component);
        payload.put("from", from);
        payload.put("to", to);
        payload.put("at", at.toString());
        try {
            dispatcher.dispatch(webhook, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Alert webhook delivery failed ({} → {}): {}", component, to, e.getMessage());
        }
    }
}
