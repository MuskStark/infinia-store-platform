package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Buy Me a Coffee purchase flow end to end with the REAL adapter (no stub,
 * no outbound HTTP — BMC links are pure redirects and the webhook arrives as a
 * signed JSON body): order → redirect to the plan's Extra link → signed
 * donation.created webhook matched by supporter email + amount → membership
 * applied. Also pins the safety rails: bad signatures rejected, dashboard test
 * events ignored, amount mismatches audited instead of applied, replays
 * idempotent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "store.pay.bmac.page-url=https://buymeacoffee.com/infinia",
                "store.pay.bmac.webhook-secret=test-whsec",
                "store.pay.order-close-interval-ms=3600000",
                "store.pay.order-close-initial-delay-ms=3600000"})
@ActiveProfiles("test")
class BmacMembershipFlowTest {

    private static final String SECRET = "test-whsec";

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planAtLevel(int level) {
        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans", List.class, null);
        return ((List<Map<String, Object>>) (List<?>) plans.getBody()).stream()
                .filter(p -> ((Number) p.get("beeLevel")).intValue() == level)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrder(String email, Object planId) {
        String token = AuthTestSupport.login(http(), null, email, SeedData.DEMO_PASSWORD);
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", Http.bearerJson(token),
                Map.of("planId", planId), Map.class);
        assertEquals(200, created.getStatusCode().value());
        return created.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String email) {
        String token = AuthTestSupport.login(http(), null, email, SeedData.DEMO_PASSWORD);
        return http().getJson("/api/v1/membership/status", Map.class,
                Http.bearer(token)).getBody();
    }

    private ResponseEntity<String> postWebhook(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-signature-sha256", hmacSha256(json, SECRET));
        return http().exchange(HttpMethod.POST, "/api/v1/payments/bmac/notify", headers, json);
    }

    private static String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String donationJson(String email, double amount, String transactionId,
            boolean liveMode) {
        return """
                {"event_id":1234,"type":"donation.created","live_mode":%s,"created":1757548800,
                 "attempt":1,"data":{"supporter_name":"Alex","supporter_email":"%s",
                 "support_note":"love it","id":98765,"object":"payment",
                 "transaction_id":"%s","status":"succeeded","refunded":"false",
                 "amount":%s,"coffee_count":1,"coffee_price":%s,"currency":"USD",
                 "support_type":"Supporter","created_at":1757548800}}
                """.formatted(liveMode, email, transactionId, amount, amount);
    }

    @Test
    @SuppressWarnings("unchecked")
    void signedDonationMatchesPendingOrderByEmailAndAmount() {
        Map<String, Object> statusBefore = status("user@infinia.local");
        assertEquals(java.util.List.of("BMAC"), statusBefore.get("channels"),
                "BMAC is the active gateway when it alone is configured");

        Map<String, Object> plan = planAtLevel(2); // FORAGER 30d, 1600 fen
        Map<String, Object> order = createOrder("user@infinia.local", plan.get("planId"));
        assertEquals("BMAC", order.get("channel"));
        // Seeded plans carry no Extra link → plain page redirect.
        assertEquals("https://buymeacoffee.com/infinia", order.get("payUrl"));

        ResponseEntity<String> webhook = postWebhook(
                donationJson("user@infinia.local", 16.0, "pi_live_ABC", true));
        assertEquals(200, webhook.getStatusCode().value());
        assertEquals("success", webhook.getBody());

        Map<String, Object> statusAfter = status("user@infinia.local");
        assertEquals(2, ((Number) statusAfter.get("effectiveBeeLevel")).intValue());
        assertNotNull(statusAfter.get("membershipExpiresAt"));

        // Replaying the same transaction id must not double anything.
        assertEquals(200, postWebhook(
                donationJson("user@infinia.local", 16.0, "pi_live_ABC", true))
                .getStatusCode().value());
        assertEquals(statusAfter.get("membershipExpiresAt"),
                status("user@infinia.local").get("membershipExpiresAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planExtraLinkBecomesThePayUrl() {
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                SeedData.DEMO_PASSWORD);
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", Http.bearerJson(admin),
                Map.of("beeLevel", 4, "durationDays", 365, "priceFen", 6600,
                        "externalUrl", "https://buymeacoffee.com/infinia/extras/queen-year"),
                Map.class);
        assertEquals(200, created.getStatusCode().value());
        assertEquals("https://buymeacoffee.com/infinia/extras/queen-year",
                created.getBody().get("externalUrl"));

        Map<String, Object> order = createOrder("reviewer@infinia.local",
                created.getBody().get("planId"));
        assertEquals("https://buymeacoffee.com/infinia/extras/queen-year",
                order.get("payUrl"));
    }

    @Test
    void badSignatureTestEventsAndMismatchesNeverGrant() {
        Map<String, Object> plan = planAtLevel(3); // GUARD 3600 fen
        createOrder("ci@infinia.local", plan.get("planId"));

        // Wrong signature → fail, nothing applied.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-signature-sha256", "dead" + "beef".repeat(14));
        ResponseEntity<String> forged = http().exchange(HttpMethod.POST,
                "/api/v1/payments/bmac/notify", headers,
                donationJson("ci@infinia.local", 36.0, "pi_evil", true));
        assertEquals(400, forged.getStatusCode().value());
        assertEquals("fail", forged.getBody());
        assertNull(status("ci@infinia.local").get("membershipLevel"));

        // Dashboard test event (live_mode=false) → acknowledged, never applied.
        assertEquals(200, postWebhook(
                donationJson("ci@infinia.local", 36.0, "pi_test_1", false))
                .getStatusCode().value());
        assertNull(status("ci@infinia.local").get("membershipLevel"));

        // Right email, wrong amount → acknowledged (audited), still PENDING.
        assertEquals(200, postWebhook(
                donationJson("ci@infinia.local", 1.0, "pi_cheap", true))
                .getStatusCode().value());
        assertNull(status("ci@infinia.local").get("membershipLevel"),
                "a $1 coffee must not buy the GUARD plan");

        // Unknown events (refunds, memberships) are acknowledged without action.
        assertEquals(200, postWebhook("""
                {"event_id":1,"type":"donation.refunded","live_mode":true,"created":1,
                 "attempt":1,"data":{"supporter_email":"ci@infinia.local","amount":36.0,
                 "status":"refunded","transaction_id":"pi_x"}}
                """).getStatusCode().value());

        // The operator sees the unmatched payment in the audit trail.
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                SeedData.DEMO_PASSWORD);
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=100",
                List.class, Http.bearer(admin));
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "membership.bmacUnmatched".equals(((Map<?, ?>) e).get("action"))));
    }
}
