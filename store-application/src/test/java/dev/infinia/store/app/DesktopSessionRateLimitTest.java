package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unauthenticated refresh endpoint throttles hammering (own context with a
 * tiny limit so the shared window of the flow tests is untouched).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "store.refresh.rate-limit-per-minute=3")
@ActiveProfiles("test")
class DesktopSessionRateLimitTest {

    @LocalServerPort
    int port;

    @Test
    void refreshIsThrottledBeyondTheConfiguredLimit() {
        Http http = new Http(port);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, http.exchangeJson(HttpMethod.POST, "/api/v1/auth/refresh",
                    null, Map.of("refreshToken", "x" + i), Map.class)
                    .getStatusCode().value());
        }
        ResponseEntity<Map> throttled = http.exchangeJson(HttpMethod.POST,
                "/api/v1/auth/refresh", null, Map.of("refreshToken", "over"), Map.class);
        assertEquals(429, throttled.getStatusCode().value());
    }
}
