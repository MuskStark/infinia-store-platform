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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetProberTest {

    private HttpServer server;
    private TargetProber prober;
    private final AtomicInteger healthStatus = new AtomicInteger(200);

    private String failedPath;

    @BeforeEach
    void startFakeStore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = exchange.getRequestURI().getPath().equals("/actuator/health")
                    ? healthStatus.get() : exchange.getRequestURI().getPath().equals(failedPath) ? 503 : 200;
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        MonitorProperties properties = new MonitorProperties(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                null, 2000L, null, null, null, null, null);
        prober = new TargetProber(properties);
    }

    @AfterEach
    void stopFakeStore() {
        server.stop(0);
    }

    @Test
    void allTargetsUpIsOperational() {
        assertEquals(Indicators.OPERATIONAL, prober.probe());
    }

    @Test
    void healthEndpointDownIsMajorOutage() {
        healthStatus.set(503);
        assertEquals(Indicators.MAJOR_OUTAGE, prober.probe());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/", "/api/v1/status", "/.well-known/openid-configuration"})
    void nonHealthFailureIsPartialOutage(String path) {
        failedPath = path;
        assertEquals(Indicators.PARTIAL_OUTAGE, prober.probe());
    }

    @Test
    void unreachableStoreIsMajorOutage() {
        int port = server.getAddress().getPort();
        server.stop(0);
        MonitorProperties dead = new MonitorProperties(
                java.net.URI.create("http://127.0.0.1:" + port),
                null, 500L, null, null, null, null, null);
        assertEquals(Indicators.MAJOR_OUTAGE, new TargetProber(dead).probe());
    }
}
