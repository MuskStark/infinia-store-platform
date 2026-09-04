package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public service-status page (需求：store 服务监控页): anonymous access, live
 * probes, a full 90-day history per component and the incident feed — all
 * reachable without any authentication, because a status page that needs a
 * login is useless during an outage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ServiceStatusTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.app.service.StatusService status;

    @Test
    @SuppressWarnings("unchecked")
    void anonymousStatusSnapshotIsComplete() {
        ResponseEntity<Map> response = new Http(port).getJson("/api/v1/status", Map.class, null);
        assertEquals(200, response.getStatusCode().value());

        Map<String, Object> page = response.getBody();
        assertNotNull(page);
        assertNotNull(page.get("checkedAt"));
        assertTrue(List.of("operational", "degraded", "partial_outage", "major_outage")
                .contains(page.get("indicator")));

        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        assertEquals(8, components.size());
        List<String> keys = components.stream().map(c -> String.valueOf(c.get("key"))).toList();
        assertEquals(List.of("api", "web", "auth", "delivery", "database", "blob",
                "scanner", "upstream"), keys);

        // The live database probe must be healthy in the test profile.
        Map<String, Object> database = components.stream()
                .filter(c -> "database".equals(c.get("key"))).findFirst().orElseThrow();
        assertEquals("operational", database.get("indicator"));
        assertNotNull(database.get("uptime90d"), "today's sample was just recorded");

        // Every component reports exactly 90 days ending today (UTC).
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (Map<String, Object> component : components) {
            List<Map<String, Object>> history =
                    (List<Map<String, Object>>) component.get("history");
            assertEquals(90, history.size());
            assertEquals(today.minusDays(89).toString(), history.get(0).get("date"));
            assertEquals(today.toString(), history.get(history.size() - 1).get("date"));
            // Past days have no samples yet on a fresh database: honest no_data,
            // not a fake 100% green history.
            Map<String, Object> oldest = history.get(0);
            assertEquals("no_data", oldest.get("indicator"));
            assertNull(oldest.get("uptimePercent"));
            Map<String, Object> latest = history.get(history.size() - 1);
            assertEquals("operational", latest.get("indicator"));
            assertNotNull(latest.get("uptimePercent"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedSnapshotsAggregateIntoTodaysBucket() {
        status.page();
        status.page();
        ResponseEntity<Map> response = new Http(port).getJson("/api/v1/status", Map.class, null);
        Map<String, Object> database = ((List<Map<String, Object>>) response.getBody()
                .get("components")).stream()
                .filter(c -> "database".equals(c.get("key"))).findFirst().orElseThrow();
        double uptime = ((Number) database.get("uptime90d")).doubleValue();
        assertEquals(100.0, uptime, 0.001, "both samples were ok");
    }

    @Test
    void incidentFeedIsPublicAndEmptyOnFreshData() {
        ResponseEntity<List> response = new Http(port)
                .getJson("/api/v1/status/incidents", List.class, null);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
    }
}
