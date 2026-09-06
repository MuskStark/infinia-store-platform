package dev.infinia.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A monitor whose store has never been reachable (dead target; the scheduler's
 * first cycle fires at boot): the page renders exactly one component — the
 * monitor's own external reachability, red — with no mirrored timestamp. It
 * must never invent mirrored components or a fake all-green page.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "monitor.target-base-url=http://127.0.0.1:1",
                "monitor.poll-interval-ms=3600000",
                // Fresh in-memory database: this store has never been mirrored.
                "spring.datasource.url=jdbc:h2:mem:monitor-cold;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        })
class MonitorColdStartTest {

    @LocalServerPort
    int port;

    @Test
    @SuppressWarnings("unchecked")
    void neverReachedStoreRendersExternalOnlyAndRed() throws Exception {
        // The boot-time poll cycle settles asynchronously; wait for its verdict.
        Map<String, Object> page = awaitExternalVerdict();

        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        assertEquals(1, components.size(), "no mirrored components may be invented");
        assertEquals("external", components.get(0).get("key"));
        assertEquals("major_outage", components.get(0).get("indicator"));
        assertEquals("major_outage", page.get("indicator"));
        assertNull(page.get("mirroredAt"));
        assertTrue((Boolean) page.get("stale"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitExternalVerdict() throws Exception {
        Map<String, Object> page = null;
        for (int i = 0; i < 100; i++) {
            page = new Http(port).getJson("/api/v1/status", Map.class).getBody();
            assertNotNull(page);
            List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
            if (components.size() == 1
                    && "major_outage".equals(components.get(0).get("indicator"))) {
                return page;
            }
            Thread.sleep(50);
        }
        return page;
    }
}
