package dev.infinia.monitor;

import com.sun.net.httpserver.HttpServer;
import dev.infinia.monitor.config.MonitorProperties;
import dev.infinia.monitor.service.Indicators;
import dev.infinia.monitor.service.TargetProber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetProberTest {

    private HttpServer server;
    private TargetProber prober;
    private final Map<String, Integer> pathStatus = new ConcurrentHashMap<>();

    @BeforeEach
    void startFakeStore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = pathStatus.getOrDefault(exchange.getRequestURI().getPath(), 200);
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        prober = new TargetProber(properties(server.getAddress().getPort(), 2000));
    }

    static MonitorProperties properties(int port, long probeTimeoutMs) {
        return new MonitorProperties(URI.create("http://127.0.0.1:" + port),
                null, null, probeTimeoutMs, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @AfterEach
    void stopFakeStore() {
        server.stop(0);
    }

    @Test
    void allTargetsUpIsOperational() {
        var outcome = prober.probe();
        assertEquals(Indicators.OPERATIONAL, outcome.indicator());
        assertFalse(outcome.diagnosticsInconclusive());
        assertEquals(4, outcome.probes().size());
    }

    @Test
    void healthEndpointDownIsMajorOutage() {
        pathStatus.put("/actuator/health", 503);
        assertEquals(Indicators.MAJOR_OUTAGE, prober.probe().indicator());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/", "/api/v1/status", "/.well-known/openid-configuration"})
    void nonHealthFailureIsPartialOutage(String path) {
        pathStatus.put(path, 503);
        assertEquals(Indicators.PARTIAL_OUTAGE, prober.probe().indicator());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {403, 404})
    void diagnosticPathAnswering4xxIsInconclusiveNotOutage(int status) {
        pathStatus.put("/actuator/health", status);
        var outcome = prober.probe();
        assertEquals(Indicators.OPERATIONAL, outcome.indicator(),
                "a WAF rule or renamed health endpoint must not read as a store outage");
        assertTrue(outcome.diagnosticsInconclusive());
    }

    @Test
    void servicePathAnswering4xxStillCountsAsFailure() {
        pathStatus.put("/api/v1/status", 404);
        assertEquals(Indicators.PARTIAL_OUTAGE, prober.probe().indicator());
    }

    @Test
    void unreachableStoreIsMajorOutage() {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertEquals(Indicators.MAJOR_OUTAGE,
                new TargetProber(properties(port, 500)).probe().indicator());
    }
}
