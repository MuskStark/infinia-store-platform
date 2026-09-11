package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admin console for membership plans and orders (管理 · 会员套餐): plan CRUD with
 * validation, public-visibility effects, PLATFORM_ADMIN-only access, and the
 * order stream with buyer info.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"store.pay.order-close-interval-ms=3600000",
                "store.pay.order-close-initial-delay-ms=3600000"})
@ActiveProfiles("test")
@Import(MembershipTestSupport.StubGatewayConfig.class)
class AdminMembershipPlanTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    private HttpHeaders json(String email) {
        return Http.bearerJson(AuthTestSupport.login(http(), null, email,
                SeedData.DEMO_PASSWORD));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planCrudValidatesAndDrivesPublicPricing() {
        HttpHeaders admin = json("admin@infinia.local");

        // Seeded defaults are visible in the console.
        ResponseEntity<List> seeded = http().getJson("/api/v1/admin/membership/plans",
                List.class, admin);
        assertEquals(200, seeded.getStatusCode().value());
        assertEquals(4, seeded.getBody().size());

        // Create: LARVA is not purchasable, terms must be sane.
        assertEquals(400, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", admin,
                Map.of("beeLevel", 0, "durationDays", 30, "priceFen", 100), Map.class)
                .getStatusCode().value());
        assertEquals(400, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", admin,
                Map.of("beeLevel", 4, "durationDays", 0, "priceFen", 100), Map.class)
                .getStatusCode().value());

        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", admin,
                Map.of("beeLevel", 4, "durationDays", 7, "priceFen", 990, "sort", 9), Map.class);
        assertEquals(200, created.getStatusCode().value());
        String planId = (String) created.getBody().get("planId");
        assertEquals(990, ((Number) created.getBody().get("priceFen")).longValue());
        assertTrue((Boolean) created.getBody().get("active"), "new plans default to on sale");

        // Appears in the public pricing immediately.
        ResponseEntity<List> publicPlans = http().getJson("/api/v1/membership/plans",
                List.class, null);
        assertEquals(5, publicPlans.getBody().size());

        // Partial update: repriced.
        ResponseEntity<Map> repriced = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/membership/plans/" + planId, admin,
                Map.of("priceFen", 1290), Map.class);
        assertEquals(200, repriced.getStatusCode().value());
        assertEquals(1290, ((Number) repriced.getBody().get("priceFen")).longValue());
        assertEquals(7, ((Number) repriced.getBody().get("durationDays")).intValue(),
                "omitted fields keep their values");

        // Off-sale hides it from buyers but keeps it in the console.
        assertEquals(200, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/membership/plans/" + planId, admin,
                Map.of("active", false), Map.class).getStatusCode().value());
        assertEquals(4, http().getJson("/api/v1/membership/plans", List.class, null)
                .getBody().size());
        assertEquals(5, http().getJson("/api/v1/admin/membership/plans", List.class, admin)
                .getBody().size());

        // Delete removes it everywhere.
        assertEquals(200, http().exchangeJson(HttpMethod.DELETE,
                "/api/v1/admin/membership/plans/" + planId, admin, null, Map.class)
                .getStatusCode().value());
        assertEquals(4, http().getJson("/api/v1/admin/membership/plans", List.class, admin)
                .getBody().size());
        assertEquals(404, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/membership/plans/" + planId, admin,
                Map.of("priceFen", 1), Map.class).getStatusCode().value(),
                "updating a deleted plan reports plan_not_found");

        // Mutations are audited.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=100",
                List.class, admin);
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "membership.planCreated".equals(((Map<?, ?>) e).get("action"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void consoleIsAdminOnlyAndOrdersCarryBuyerInfo() {
        HttpHeaders user = json("user@infinia.local");
        HttpHeaders admin = json("admin@infinia.local");

        assertEquals(403, http().getJson("/api/v1/admin/membership/plans", List.class,
                user).getStatusCode().value());
        assertEquals(403, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/membership/plans", user,
                Map.of("beeLevel", 4, "durationDays", 7, "priceFen", 990), Map.class)
                .getStatusCode().value());
        assertEquals(403, http().getJson("/api/v1/admin/membership/orders", List.class,
                user).getStatusCode().value());

        // A purchase shows up in the admin stream with buyer info.
        ResponseEntity<List> plans = http().getJson("/api/v1/membership/plans", List.class,
                admin);
        Map<String, Object> plan = ((List<Map<String, Object>>) (List<?>) plans.getBody())
                .stream()
                .filter(p -> ((Number) p.get("beeLevel")).intValue() == 2)
                .findFirst().orElseThrow();
        assertEquals(200, http().exchangeJson(HttpMethod.POST, "/api/v1/membership/orders",
                user, Map.of("planId", plan.get("planId")), Map.class).getStatusCode().value());

        ResponseEntity<List> orders = http().getJson("/api/v1/admin/membership/orders",
                List.class, admin);
        assertEquals(200, orders.getStatusCode().value());
        Map<String, Object> latest = (Map<String, Object>) orders.getBody().get(0);
        assertEquals("user@infinia.local", latest.get("email"));
        assertEquals("PENDING", latest.get("status"));
        assertEquals(2, ((Number) latest.get("targetLevel")).intValue());
    }
}
