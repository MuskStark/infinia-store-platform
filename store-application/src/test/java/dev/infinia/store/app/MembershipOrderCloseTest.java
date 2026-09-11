package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import dev.infinia.store.app.service.MembershipService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Order lifecycle around the payment window: the closing job shuts PENDING
 * orders whose window lapsed (the scheduler itself is neutralized with a
 * far-future interval, mirroring the monitor-polling test convention), and a
 * late gateway callback still converts a CLOSED order to PAID because the
 * money moved — the buyer keeps what they paid for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"store.pay.order-close-interval-ms=3600000",
                "store.pay.order-close-initial-delay-ms=3600000"})
@ActiveProfiles("test")
@Import(MembershipTestSupport.StubGatewayConfig.class)
class MembershipOrderCloseTest {

    @LocalServerPort
    int port;

    @Autowired
    MembershipService membership;

    @Autowired
    MembershipTestSupport.StubPaymentGateway gateway;

    @Autowired
    dev.infinia.store.domain.port.BillingRepositories.MembershipOrderRepository orders;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredOrderClosesAndLatePaymentStillApplies() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                SeedData.DEMO_PASSWORD);
        HttpHeaders json = Http.bearerJson(token);

        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans",
                List.class, null);
        Map<String, Object> plan = ((List<Map<String, Object>>) (List<?>) plans.getBody())
                .stream()
                .filter(p -> ((Number) p.get("beeLevel")).intValue() == 2)
                .findFirst().orElseThrow();

        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", json,
                Map.of("planId", plan.get("planId")), Map.class);
        String orderNo = (String) created.getBody().get("orderNo");
        long priceFen = ((Number) created.getBody().get("priceFen")).longValue();

        // Age the order past its window, then run the (disabled-scheduler) job by hand.
        var order = orders.findByOrderNo(orderNo).orElseThrow();
        orders.updateExpiresAt(order.id, Instant.now().minus(1, ChronoUnit.MINUTES));
        membership.closeExpiredOrders();

        Map<String, Object> closed = http().getJson("/api/v1/membership/orders/" + orderNo,
                Map.class, Http.bearer(token)).getBody();
        assertEquals("CLOSED", closed.get("status"));

        // A paid callback for the closed order still lands: mark PAID, apply.
        ResponseEntity<String> late = http().postForm("/api/v1/payments/xunhu/notify",
                gateway.notifyFor(orderNo, priceFen, "STUB-LATE-1"), null);
        assertEquals(200, late.getStatusCode().value());
        assertEquals("success", late.getBody());

        Map<String, Object> paid = http().getJson("/api/v1/membership/orders/" + orderNo,
                Map.class, Http.bearer(token)).getBody();
        assertEquals("PAID", paid.get("status"));

        Map<String, Object> status = http().getJson("/api/v1/membership/status", Map.class,
                Http.bearer(token)).getBody();
        assertEquals(2, ((Number) status.get("effectiveBeeLevel")).intValue());
        assertNotNull(status.get("membershipExpiresAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lazyCloseReportsExpiredPendingOrders() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                SeedData.DEMO_PASSWORD);

        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans",
                List.class, null);
        Map<String, Object> plan = ((List<Map<String, Object>>) (List<?>) plans.getBody())
                .stream()
                .filter(p -> ((Number) p.get("beeLevel")).intValue() == 2)
                .findFirst().orElseThrow();
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/membership/orders", Http.bearerJson(token),
                Map.of("planId", plan.get("planId")), Map.class);
        String orderNo = (String) created.getBody().get("orderNo");

        var order = orders.findByOrderNo(orderNo).orElseThrow();
        orders.updateExpiresAt(order.id, Instant.now().minusSeconds(5));

        // The plain GET closes it lazily without the scheduler.
        Map<String, Object> body = http().getJson("/api/v1/membership/orders/" + orderNo,
                Map.class, Http.bearer(token)).getBody();
        assertEquals("CLOSED", body.get("status"));
    }
}
