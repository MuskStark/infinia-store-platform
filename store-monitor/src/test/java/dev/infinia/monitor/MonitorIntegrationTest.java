package dev.infinia.monitor;

import com.sun.net.httpserver.HttpServer;
import dev.infinia.monitor.persistence.MirrorSnapshotRepository;
import dev.infinia.monitor.service.PollCycle;
import dev.infinia.monitor.service.StatusMirror;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end monitor behaviour against a fake store: a healthy cycle mirrors
 * the store's components; a failing store needs two probe rounds (确认中, then
 * confirmed) before the external component turns red, freezes the snapshot
 * (the page keeps rendering, past the stale window with a stale flag) and
 * opens the outage incident; recovery is equally confirmed before the incident
 * resolves — and a monitor restart during an outage still renders the
 * persisted snapshot.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Drive PollCycle manually; keep the scheduler out of the assertions.
                "monitor.probe-interval-ms=3600000",
                "monitor.mirror-interval-ms=3600000",
                "monitor.rollup-interval-ms=3600000",
                "monitor.stale-after-ms=30000",
                // Dedicated in-memory database: this class is a stateful sequence.
                "spring.datasource.url=jdbc:h2:mem:monitor-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MonitorIntegrationTest {

    /** The fake store's status page: two mirrored components, both healthy. */
    private static final String STORE_PAGE = """
            {
              "indicator": "operational",
              "checkedAt": "2026-09-06T10:00:00Z",
              "components": [
                {"key": "api", "indicator": "operational", "uptime90d": 99.9, "history": []},
                {"key": "database", "indicator": "operational", "uptime90d": 99.8, "history": []}
              ]
            }
            """;

    /** Flips the whole fake store between healthy and failing-everything. */
    static final AtomicBoolean storeHealthy = new AtomicBoolean(true);
    private static HttpServer fakeStore;

    @DynamicPropertySource
    static void fakeStoreUrl(DynamicPropertyRegistry registry) throws Exception {
        fakeStore = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeStore.createContext("/", MonitorIntegrationTest::serve);
        fakeStore.start();
        registry.add("monitor.target-base-url",
                () -> "http://127.0.0.1:" + fakeStore.getAddress().getPort());
    }

    static void serve(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            int status = storeHealthy.get() ? 200 : 503;
            String path = exchange.getRequestURI().getPath();
            String body = status == 200 && "/api/v1/status".equals(path)
                    ? STORE_PAGE : (status == 200 ? "[]" : "{\"status\":\"DOWN\"}");
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
            // client hung up; nothing to assert
        }
    }

    @AfterAll
    static void stopFakeStore() {
        if (fakeStore != null) {
            fakeStore.stop(0);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    PollCycle pollCycle;

    @Autowired
    StatusMirror mirror;

    @Autowired
    MirrorSnapshotRepository snapshots;

    /** Two probe rounds: enough to confirm any transition with thresholds of 2. */
    private void runCycles(int rounds) {
        for (int i = 0; i < rounds; i++) {
            pollCycle.cycle();
        }
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void healthyCycleMirrorsStoreAndExternalComponent() {
        runCycles(1); // the first real observation confirms immediately

        Map<String, Object> page = get("/api/v1/status");

        assertEquals("operational", page.get("indicator"));
        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        assertEquals(3, components.size(), "two mirrored + external");
        assertEquals("api", components.get(0).get("key"));
        assertEquals("external", components.get(2).get("key"));
        assertEquals("operational", components.get(2).get("indicator"));
        assertNotNull(page.get("mirroredAt"));
        assertFalse((Boolean) page.get("stale"));
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void failingStoreFreezesSnapshotAndOpensIncident() throws Exception {
        runCycles(1);
        StatusMirror.Snapshot before = mirror.current();
        assertNotNull(before);

        storeHealthy.set(false);
        runCycles(1); // first failing probe: 确认中, page must not flip yet

        Map<String, Object> page = get("/api/v1/status");
        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        Map<String, Object> external = components.get(2);
        assertEquals("operational", external.get("indicator"),
                "one failed probe must not flip the external component");
        assertEquals(Boolean.TRUE, external.get("pending"), "the page says 确认中 instead");
        assertEquals(0, incidents().size(), "no incident before the fault is confirmed");

        runCycles(1); // second failing probe: confirmed

        page = get("/api/v1/status");
        components = (List<Map<String, Object>>) page.get("components");
        assertEquals(3, components.size(), "frozen internals still render");
        assertEquals("operational", components.get(0).get("indicator"),
                "frozen component keeps last-known state");
        assertEquals("external", components.get(2).get("key"));
        assertEquals("major_outage", components.get(2).get("indicator"));
        assertFalse((Boolean) components.get(2).get("pending"));
        assertEquals("major_outage", page.get("indicator"));

        List<Map<String, Object>> incidents = incidents();
        assertEquals(1, incidents.size());
        assertEquals("external", incidents.get(0).get("component"));
        assertEquals("investigating", incidents.get(0).get("status"));

        assertEquals(before.fetchedAt().toString(), page.get("mirroredAt"),
                "fetchedAt stays frozen at the last successful fetch");

        // Age the persisted fixture instead of racing a 300 ms wall-clock
        // window against HTTP startup and database work on slower CI hosts.
        var stored = snapshots.findById(1).orElseThrow();
        stored.fetchedAt = before.fetchedAt().minusSeconds(31)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        snapshots.save(stored);
        mirror.restore();
        page = get("/api/v1/status");
        assertTrue((Boolean) page.get("stale"));
        assertEquals(stored.fetchedAt.toString(), page.get("mirroredAt"),
                "an aged snapshot remains visible during the outage");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void recoveryResolvesTheIncident() {
        storeHealthy.set(true);
        runCycles(1); // first healthy probe: recovery being confirmed, incident stays
        assertEquals("investigating", incidents().get(0).get("status"));

        runCycles(1); // second healthy probe: recovery confirmed

        List<Map<String, Object>> incidents = incidents();
        assertEquals(1, incidents.size());
        assertEquals("resolved", incidents.get(0).get("status"));
        assertNotNull(incidents.get(0).get("resolvedAt"));

        Map<String, Object> page = get("/api/v1/status");
        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        assertEquals("operational", components.get(2).get("indicator"));
        assertFalse((Boolean) page.get("stale"));
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void monitorRestartDuringOutageStillRendersPersistedSnapshot() throws Exception {
        storeHealthy.set(false);
        runCycles(2); // outage recorded, snapshot frozen

        // Simulate the restart: reload the persisted snapshot from disk.
        mirror.restore();
        Map<String, Object> page = get("/api/v1/status");
        List<Map<String, Object>> components = (List<Map<String, Object>>) page.get("components");
        assertEquals(3, components.size(), "frozen internals survive the restart");
        assertNotNull(page.get("mirroredAt"));

        storeHealthy.set(true); // leave the world healthy for other test classes
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return new Http(port).getJson(path, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> incidents() {
        return (List<Map<String, Object>>) (List<?>) new Http(port)
                .getJson("/api/v1/status/incidents", List.class).getBody();
    }
}
