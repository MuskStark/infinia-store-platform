package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SPA's /status deep link hands off to the monitor's public address
 * (ADR-011). That address is runtime config — STORE_MONITOR_PUBLIC_URL served
 * anonymously from /api/v1/status/monitor — so prebuilt images redirect to
 * the deployment's status page without rebuilding, and an unconfigured
 * deployment answers null instead of guessing an address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:store-monitor-link;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "store.monitor-public-url=https://status.example.com"})
@ActiveProfiles("test")
class StatusMonitorLinkTest {

    @LocalServerPort
    int port;

    @Test
    void servesConfiguredMonitorAddressAnonymously() {
        ResponseEntity<Map> response =
                new Http(port).getJson("/api/v1/status/monitor", Map.class, null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("url", "https://status.example.com"), response.getBody());
    }

    @Test
    void blankConfigurationDegradesToNullNotAGuess() {
        // The canonical constructor normalizes blank to null: no default
        // monitor address is ever invented on the user's behalf.
        dev.infinia.store.app.config.StoreProperties properties =
                new dev.infinia.store.app.config.StoreProperties(
                        null, "   ", null, null, null, null, 0, 0, 0, null, null, null,
                        null, null, null, null, null);
        assertNull(properties.monitorPublicUrl());
    }
}
