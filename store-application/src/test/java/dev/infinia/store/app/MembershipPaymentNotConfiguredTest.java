package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graceful degradation without gateway credentials: the real XunHuPay adapter
 * is wired but unconfigured (the test profile never sets store.pay.xunhu.*),
 * so buying reports {@code payment_not_configured} — pricing and status still
 * work, nothing else breaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"store.pay.order-close-interval-ms=3600000",
                "store.pay.order-close-initial-delay-ms=3600000"})
@ActiveProfiles("test")
class MembershipPaymentNotConfiguredTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buyingReportsPaymentNotConfiguredButPricingStaysPublic() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                SeedData.DEMO_PASSWORD);

        assertEquals(200, http().getJson("/api/v1/membership/plans", List.class, null)
                .getStatusCode().value(), "pricing stays readable");

        Map<String, Object> status = http().getJson("/api/v1/membership/status", Map.class,
                Http.bearer(token)).getBody();
        assertTrue(((List<?>) status.get("channels")).isEmpty(),
                "no payable channels without credentials");

        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans", List.class, null);
        Map<String, Object> plan = (Map<String, Object>) plans.getBody().get(0);

        ResponseEntity<Map> order = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", Http.bearerJson(token),
                Map.of("planId", plan.get("planId")), Map.class);
        assertEquals(503, order.getStatusCode().value());
        assertEquals("payment_not_configured", order.getBody().get("code"));
    }
}
