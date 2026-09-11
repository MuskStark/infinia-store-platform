package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Infinia Level purchase flow (会员等级购买) end to end: public pricing,
 * order creation against a stub gateway, the signed gateway callback on the
 * public notify endpoint, the time-limited membership that raises the effective
 * ladder position, renewals, and the rejection paths (downgrade purchases,
 * forged signatures, amount tampering, replayed callbacks).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"store.pay.order-close-interval-ms=3600000",
                "store.pay.order-close-initial-delay-ms=3600000"})
@ActiveProfiles("test")
@Import(MembershipTestSupport.StubGatewayConfig.class)
class MembershipPurchaseFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    MembershipTestSupport.StubPaymentGateway gateway;

    @Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    Http http() {
        return new Http(port);
    }

    private String login(String email) {
        return AuthTestSupport.login(http(), null, email, SeedData.DEMO_PASSWORD);
    }

    private HttpHeaders json(String token) {
        return Http.bearerJson(token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planAtLevel(int level) {
        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans", List.class, null);
        assertEquals(200, plans.getStatusCode().value());
        List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) plans.getBody();
        return items.stream()
                .filter(p -> ((Number) p.get("beeLevel")).intValue() == level)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrder(String token, Map<String, Object> plan) {
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", json(token),
                Map.of("planId", plan.get("planId"), "channel", "WECHAT"), Map.class);
        assertEquals(200, created.getStatusCode().value());
        return created.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String token) {
        return http().getJson("/api/v1/membership/status", Map.class,
                Http.bearer(token)).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> order(String token, String orderNo) {
        return http().getJson("/api/v1/membership/orders/" + orderNo, Map.class,
                Http.bearer(token)).getBody();
    }

    private ResponseEntity<String> notifyGateway(Map<String, String> params) {
        return http().postForm("/api/v1/payments/xunhu/notify", params, null);
    }

    private static Instant truncatedNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** The seeded markdown listing's gate, restored after each test. */
    @AfterEach
    void resetGate() {
        dev.infinia.store.domain.model.Listing listing = listings.findByCoordinate(
                dev.infinia.store.contract.coordinate.InfiniaCoordinate
                        .parse("infinia://plugin/official/markdown")).orElseThrow();
        listing.minBeeLevel = 0;
        listings.save(listing);
    }

    @Test
    void plansArePublicPricingWithSeededDefaults() {
        ResponseEntity<List> anonymous = http().getJson("/api/v1/membership/plans",
                List.class, null);
        assertEquals(200, anonymous.getStatusCode().value());
        List<Map<String, Object>> plans = anonymous.getBody();
        assertEquals(4, plans.size(), "one seeded plan per purchasable level");
        assertTrue(plans.stream().allMatch(p -> ((Number) p.get("priceFen")).longValue() > 0));
        assertTrue(plans.stream().allMatch(p -> ((Number) p.get("durationDays")).intValue() > 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void paidOrderRaisesEffectiveLevelAndOpensGatedListing() {
        String user = login("user@infinia.local"); // seeded WORKER (base 1)

        Map<String, Object> before = status(user);
        assertEquals(1, ((Number) before.get("baseBeeLevel")).intValue());
        assertEquals(1, ((Number) before.get("effectiveBeeLevel")).intValue());
        assertNull(before.get("membershipLevel"), "no membership row yet");
        List<String> channels = (List<String>) before.get("channels");
        assertEquals(List.of("WECHAT", "ALIPAY"), channels);

        Map<String, Object> order = createOrder(user, planAtLevel(3)); // GUARD 90d
        String orderNo = (String) order.get("orderNo");
        assertEquals("PENDING", order.get("status"));
        assertEquals(3600, ((Number) order.get("priceFen")).longValue());
        assertNotNull(order.get("payUrl"), "cashier URL for the buyer to open");

        // The buyer's own order is readable; another account's is not.
        assertEquals(403, http().getJson("/api/v1/membership/orders/" + orderNo, Map.class,
                Http.bearer(login("reviewer@infinia.local"))).getStatusCode().value());

        // The gateway's signed callback flips the order and applies the membership.
        ResponseEntity<String> notify = notifyGateway(
                gateway.notifyFor(orderNo, 3600, "STUB-TRADE-1"));
        assertEquals(200, notify.getStatusCode().value());
        assertEquals("success", notify.getBody());

        assertEquals("PAID", order(user, orderNo).get("status"));
        assertNull(order(user, orderNo).get("payUrl"), "paid orders hide the one-shot URL");

        Map<String, Object> after = status(user);
        assertEquals(3, ((Number) after.get("effectiveBeeLevel")).intValue());
        assertEquals(3, ((Number) after.get("membershipLevel")).intValue());
        Instant expiry = Instant.parse((String) after.get("membershipExpiresAt"));
        assertTrue(expiry.isAfter(truncatedNow().plus(89, ChronoUnit.DAYS))
                        && expiry.isBefore(truncatedNow().plus(91, ChronoUnit.DAYS)),
                "90-day window, got " + expiry);

        // /me carries the effective level for the SPA badge.
        Map<String, Object> me = http().getJson("/api/v1/me", Map.class,
                Http.bearer(user)).getBody();
        assertEquals(1, ((Number) me.get("beeLevel")).intValue(), "base level unchanged");
        assertEquals(3, ((Number) me.get("effectiveBeeLevel")).intValue());

        // The purchased level gates real listing access (listing gate = 3).
        UUID listingId = listings.findByCoordinate(dev.infinia.store.contract.coordinate
                .InfiniaCoordinate.parse("infinia://plugin/official/markdown")).orElseThrow().id;
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/min-bee-level",
                json(login("admin@infinia.local")), Map.of("minBeeLevel", 3),
                Map.class).getStatusCode().value());
        assertEquals(200, http().getJson("/api/v1/listings/official/markdown", Map.class,
                Http.bearer(user)).getStatusCode().value(), "membership meets gate(3)");
        // A gate above the purchased level still refuses.
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/min-bee-level",
                json(login("admin@infinia.local")), Map.of("minBeeLevel", 4),
                Map.class).getStatusCode().value());
        assertEquals(403, http().getJson("/api/v1/listings/official/markdown", Map.class,
                Http.bearer(user)).getStatusCode().value(), "membership below gate(4)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void renewalExtendsExpiryAndReplayedCallbackIsIdempotent() {
        // A dedicated account keeps this test independent of the other methods'
        // purchases on user@ (methods share this class's fresh database).
        String buyer = login("reviewer@infinia.local"); // base LARVA (0)

        Map<String, Object> first = createOrder(buyer, planAtLevel(2)); // FORAGER 30d
        notifyGateway(gateway.notifyFor((String) first.get("orderNo"), 1600, "STUB-TRADE-2"));
        Instant firstExpiry = Instant.parse((String) status(buyer).get("membershipExpiresAt"));
        assertTrue(firstExpiry.isAfter(truncatedNow().plus(29, ChronoUnit.DAYS)));

        // Same-tier purchase is a renewal: 30 more days from the current deadline.
        Map<String, Object> renewal = createOrder(buyer, planAtLevel(2));
        assertEquals(200, notifyGateway(gateway.notifyFor(
                (String) renewal.get("orderNo"), 1600, "STUB-TRADE-3")).getStatusCode().value());
        Instant renewed = Instant.parse((String) status(buyer).get("membershipExpiresAt"));
        assertTrue(renewed.isAfter(firstExpiry.plus(29, ChronoUnit.DAYS))
                        && renewed.isBefore(firstExpiry.plus(31, ChronoUnit.DAYS)),
                "renewal extends by 30d, got " + renewed + " after " + firstExpiry);

        // Replaying the second callback must not double-extend.
        assertEquals(200, notifyGateway(gateway.notifyFor(
                (String) renewal.get("orderNo"), 1600, "STUB-TRADE-3")).getStatusCode().value());
        assertEquals(renewed.toString(), status(buyer).get("membershipExpiresAt"));

        // Buying below the effective level is refused before any money moves.
        ResponseEntity<Map> downgrade = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", json(buyer),
                Map.of("planId", planAtLevel(1).get("planId")), Map.class);
        assertEquals(409, downgrade.getStatusCode().value());
        assertEquals("membership_purchase_level_too_low",
                ((Map<String, Object>) downgrade.getBody()).get("code"));
        assertTrue(gateway.requests.stream().noneMatch(r ->
                r.amountFen() == 600), "no gateway call for the refused downgrade");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgedSignatureAndTamperedAmountAreRejected() {
        String buyer = login("ci@infinia.local"); // dedicated account, never paid here
        Map<String, Object> order = createOrder(buyer, planAtLevel(2));
        String orderNo = (String) order.get("orderNo");

        // Wrong signature: the endpoint answers "fail" and nothing is applied.
        ResponseEntity<String> forged = notifyGateway(
                gateway.tamperedNotify(orderNo, 1600, "STUB-EVIL"));
        assertEquals(400, forged.getStatusCode().value());
        assertEquals("fail", forged.getBody());
        assertEquals("PENDING", order(buyer, orderNo).get("status"));
        assertNull(status(buyer).get("membershipLevel"));

        // Correct signature but wrong amount: acknowledged as fail, never applied.
        ResponseEntity<String> cheap = notifyGateway(
                gateway.notifyFor(orderNo, 1, "STUB-CHEAP"));
        assertEquals(400, cheap.getStatusCode().value());
        assertEquals("fail", cheap.getBody());
        assertEquals("PENDING", order(buyer, orderNo).get("status"));
        assertNull(status(buyer).get("membershipLevel"),
                "amount mismatch must not grant a membership");

        // Unknown order numbers fail too.
        assertEquals(400, notifyGateway(
                gateway.notifyFor("MEM-NO-SUCH-ORDER", 1600, "STUB-X")).getStatusCode().value());

        // The audit trail keeps the amount mismatch for the operator.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=100",
                List.class, Http.bearer(login("admin@infinia.local")));
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "membership.amountMismatch".equals(((Map<?, ?>) e).get("action"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inactivePlansAndUnknownChannelsAreRefused() {
        String admin = login("admin@infinia.local");
        String user = login("user@infinia.local");

        // Create a dedicated plan, then take it off sale.
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", json(admin),
                Map.of("beeLevel", 4, "durationDays", 7, "priceFen", 990), Map.class);
        assertEquals(200, created.getStatusCode().value());
        String planId = (String) created.getBody().get("planId");

        assertEquals(200, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/membership/plans/" + planId, json(admin),
                Map.of("active", false), Map.class).getStatusCode().value());

        ResponseEntity<Map> offSale = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", json(user), Map.of("planId", planId), Map.class);
        assertEquals(409, offSale.getStatusCode().value());
        assertEquals("membership_plan_inactive", offSale.getBody().get("code"));

        // Unknown channels never reach the gateway.
        ResponseEntity<Map> badChannel = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", json(user),
                Map.of("planId", planAtLevel(2).get("planId"), "channel", "PAYPAL"), Map.class);
        assertEquals(400, badChannel.getStatusCode().value());
    }
}
