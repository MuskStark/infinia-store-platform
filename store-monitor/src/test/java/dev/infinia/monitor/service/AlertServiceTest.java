package dev.infinia.monitor.service;

import dev.infinia.monitor.config.MonitorProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertServiceTest {

    private record Dispatch(String url, String json) {}

    @Test
    void transitionsAlertOnceAndThrottleSuppressesFlapping() {
        List<Dispatch> sent = new ArrayList<>();
        AlertService alerts = testable(sent, 15);

        Instant t0 = Instant.parse("2026-09-06T10:00:00Z");
        // First sighting seeds silently — a monitor booting must not alert.
        alerts.onIndicators(Map.of("external", "operational"), t0);
        assertTrue(sent.isEmpty());

        // Store dies: one alert.
        alerts.onIndicators(Map.of("external", "major_outage"), t0.plusSeconds(60));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).json().contains("\"to\":\"major_outage\""));

        // Recovery and a second outage within the throttle window: suppressed.
        alerts.onIndicators(Map.of("external", "operational"), t0.plusSeconds(120));
        alerts.onIndicators(Map.of("external", "major_outage"), t0.plusSeconds(180));
        assertEquals(1, sent.size());

        // After the throttle window expires, the next transition alerts again.
        alerts.onIndicators(Map.of("external", "operational"), t0.plusSeconds(240));
        alerts.onIndicators(Map.of("external", "major_outage"), t0.plusSeconds(16 * 60));
        assertEquals(2, sent.size());
    }

    @Test
    void steadyStatesNeverAlert() {
        List<Dispatch> sent = new ArrayList<>();
        AlertService alerts = testable(sent, 15);
        Instant now = Instant.now();
        // Seed, then hold the same (even degraded) indicator: silence.
        alerts.onIndicators(Map.of("database", "operational"), now);
        for (int i = 1; i < 10; i++) {
            alerts.onIndicators(Map.of("database", "degraded"), now.plusSeconds(i * 60));
        }
        assertEquals(1, sent.size(), "exactly one transition alert, then silence");
    }

    private AlertService testable(List<Dispatch> sent, int throttleMinutes) {
        MonitorProperties properties = new MonitorProperties(
                URI.create("https://store.example.com"), null, null,
                "/tmp/monitor-test", "https://alerts.example.com/hook", throttleMinutes,
                null, null);
        return new AlertService(properties, JsonMapper.builder().build(),
                (url, json) -> sent.add(new Dispatch(url, json)));
    }
}
