package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Community moderation, organization administration, account security and the
 * library update view (design §7.3, §7.4, §12.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ModerationAndAdminFlowTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void ratingsUpsertAndAnonymousSummary() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        ResponseEntity<Map> first = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/listings/official/markdown/ratings", jsonAuth(token),
                Map.of("stars", 5, "comment", "indispensable"), Map.class);
        assertEquals(200, first.getStatusCode().value());

        ResponseEntity<Map> page = http().getJson("/api/v1/listings/official/markdown/ratings",
                Map.class, null);
        assertEquals(200, page.getStatusCode().value());
        Map<String, Object> summary = (Map<String, Object>) page.getBody().get("summary");
        assertEquals(1L, ((Number) summary.get("count")).longValue());
        assertEquals(5.0, ((Number) summary.get("average")).doubleValue(), 0.001);

        // Re-rating updates instead of duplicating (one per user per listing).
        ResponseEntity<Map> again = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/listings/official/markdown/ratings", jsonAuth(token),
                Map.of("stars", 3), Map.class);
        assertEquals(200, again.getStatusCode().value());
        page = http().getJson("/api/v1/listings/official/markdown/ratings", Map.class, null);
        summary = (Map<String, Object>) page.getBody().get("summary");
        assertEquals(1L, ((Number) summary.get("count")).longValue());
        assertEquals(3.0, ((Number) summary.get("average")).doubleValue(), 0.001);

        ResponseEntity<Map> invalid = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/listings/official/markdown/ratings", jsonAuth(token),
                Map.of("stars", 9), Map.class);
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("validation_failed", invalid.getBody().get("code"));

        ResponseEntity<Map> anonymous = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/listings/official/markdown/ratings", jsonAuth(null),
                Map.of("stars", 4), Map.class);
        assertEquals(401, anonymous.getStatusCode().value());
    }

    @Test
    void reportsRoundTripThroughAdminConsole() {
        String userToken = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        ResponseEntity<Map> invalidReason = http().exchangeJson(HttpMethod.POST, "/api/v1/reports",
                jsonAuth(userToken), Map.of("coordinate", "infinia://plugin/official/markdown",
                        "reason", "revenge"), Map.class);
        assertEquals(400, invalidReason.getStatusCode().value());

        ResponseEntity<Map> filed = http().exchangeJson(HttpMethod.POST, "/api/v1/reports",
                jsonAuth(userToken), Map.of("coordinate", "infinia://plugin/official/markdown",
                        "reason", "malware", "details", "bundle contains a suspicious binary"),
                Map.class);
        assertEquals(201, filed.getStatusCode().value());
        assertEquals("OPEN", filed.getBody().get("status"));
        String reportId = (String) filed.getBody().get("reportId");

        ResponseEntity<Map> duplicate = http().exchangeJson(HttpMethod.POST, "/api/v1/reports",
                jsonAuth(userToken), Map.of("coordinate", "infinia://plugin/official/markdown",
                        "reason", "spam"), Map.class);
        assertEquals(409, duplicate.getStatusCode().value());

        // Non-admins cannot read or resolve the queue.
        assertEquals(403, http().getJson("/api/v1/admin/reports", List.class, Http.bearer(userToken))
                .getStatusCode().value());

        ResponseEntity<List> queue = http().getJson("/api/v1/admin/reports?status=OPEN",
                List.class, Http.bearer(adminToken));
        assertEquals(200, queue.getStatusCode().value());
        Map<String, Object> queued = findByReportId(queue.getBody(), reportId);

        ResponseEntity<Map> resolved = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/reports/" + reportId + "/resolution", jsonAuth(adminToken),
                Map.of("resolution", "ACTIONED", "note", "quarantined release"), Map.class);
        assertEquals(200, resolved.getStatusCode().value());
        assertEquals("ACTIONED", resolved.getBody().get("status"));

        ResponseEntity<Map> reResolved = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/reports/" + reportId + "/resolution", jsonAuth(adminToken),
                Map.of("resolution", "DISMISSED"), Map.class);
        assertEquals(409, reResolved.getStatusCode().value());

        // The resolution is visible in the audit trail.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events", List.class,
                Http.bearer(adminToken));
        assertEquals(200, audit.getStatusCode().value());
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "report.actioned".equals(((Map<?, ?>) e).get("action"))));
    }

    @Test
    void changePasswordRequiresCurrentAndApplies() {
        String email = "pw-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register",
                jsonAuth(null), Map.of("email", email, "password", "FirstPass123!",
                        "displayName", "PW User"), Map.class).getStatusCode().value());

        String token = AuthTestSupport.login(http(), null, email, "FirstPass123!");

        ResponseEntity<Map> wrong = http().exchangeJson(HttpMethod.PUT, "/api/v1/me/password",
                jsonAuth(token), Map.of("currentPassword", "NotThePassword1!",
                        "newPassword", "SecondPass123!"), Map.class);
        assertEquals(400, wrong.getStatusCode().value());
        assertEquals("wrong_password", wrong.getBody().get("code"));

        ResponseEntity<Map> ok = http().exchangeJson(HttpMethod.PUT, "/api/v1/me/password",
                jsonAuth(token), Map.of("currentPassword", "FirstPass123!",
                        "newPassword", "SecondPass123!"), Map.class);
        assertEquals(200, ok.getStatusCode().value());

        String freshToken = AuthTestSupport.login(http(), null, email, "SecondPass123!");
        assertEquals(200, http().getJson("/api/v1/me", Map.class, Http.bearer(freshToken))
                .getStatusCode().value());
    }

    @Test
    void installedAndUpdatesDerivedFromTelemetry() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String key = "upd-" + UUID.randomUUID().toString().substring(0, 8);
        String event = """
                [{"idempotencyKey":"%s","coordinate":"infinia://plugin/official/markdown",
                  "version":"1.0.0","type":"PLUGIN","action":"install","outcome":"success"}]
                """.formatted(key);
        ResponseEntity<Integer> accepted = http().exchangeJson(HttpMethod.POST,
                "/api/v1/install-events", jsonAuth(token), event, Integer.class);
        assertEquals(202, accepted.getStatusCode().value());

        ResponseEntity<List> installed = http().getJson("/api/v1/me/installed", List.class,
                Http.bearer(token));
        Map<String, Object> row = (Map<String, Object>) installed.getBody().stream()
                .filter(i -> ((Map<?, ?>) i).get("coordinate")
                        .equals("infinia://plugin/official/markdown"))
                .findFirst().orElseThrow();
        // Seeded latest markdown release is 2.4.0 → 1.0.0 must be flagged updatable.
        assertEquals("2.4.0", row.get("latestVersion"));
        assertEquals(Boolean.TRUE, row.get("updateAvailable"));

        ResponseEntity<List> updates = http().getJson("/api/v1/me/updates", List.class,
                Http.bearer(token));
        assertTrue(updates.getBody().stream().anyMatch(i ->
                ((Map<?, ?>) i).get("updateAvailable") == Boolean.TRUE));

        // Uninstall removes it from the installed view (ADR-009 hint semantics).
        String uninstall = """
                [{"idempotencyKey":"%s-u","coordinate":"infinia://plugin/official/markdown",
                  "version":"2.4.0","action":"uninstall","outcome":"success"}]
                """.formatted(key);
        assertEquals(202, http().exchangeJson(HttpMethod.POST, "/api/v1/install-events",
                jsonAuth(token), uninstall, Integer.class).getStatusCode().value());
        assertTrue(http().getJson("/api/v1/me/installed", List.class, Http.bearer(token)).getBody()
                .stream().noneMatch(i -> ((Map<?, ?>) i).get("coordinate")
                        .equals("infinia://plugin/official/markdown")));
    }

    @Test
    void organizationMemberAdministration() {
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String userToken = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        String slug = "org-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> org = http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(adminToken), Map.of("slug", slug, "name", "Admin Org"), Map.class);
        assertEquals(201, org.getStatusCode().value());
        String orgId = (String) org.getBody().get("organizationId");

        ResponseEntity<Map> added = http().exchangeJson(HttpMethod.POST,
                "/api/v1/organizations/" + orgId + "/members", jsonAuth(adminToken),
                Map.of("email", "user@infinia.local", "role", "PUBLISHER"), Map.class);
        assertEquals(201, added.getStatusCode().value());
        assertEquals("PUBLISHER", added.getBody().get("role"));
        String memberId = (String) added.getBody().get("userId");

        // A plain member cannot administer.
        assertEquals(403, http().exchangeJson(HttpMethod.POST,
                "/api/v1/organizations/" + orgId + "/members", jsonAuth(userToken),
                Map.of("email", "reviewer@infinia.local"), Map.class).getStatusCode().value());

        ResponseEntity<Map> promoted = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/organizations/" + orgId + "/members/" + memberId + "/role",
                jsonAuth(adminToken), Map.of("role", "ORG_ADMIN"), Map.class);
        assertEquals(200, promoted.getStatusCode().value());
        assertEquals("ORG_ADMIN", promoted.getBody().get("role"));

        ResponseEntity<List> members = http().getJson(
                "/api/v1/organizations/" + orgId + "/members", List.class, Http.bearer(userToken));
        assertEquals(200, members.getStatusCode().value());

        ResponseEntity<String> removed = http().exchange(HttpMethod.DELETE,
                "/api/v1/organizations/" + orgId + "/members/" + memberId,
                Http.bearer(adminToken), null);
        assertEquals(204, removed.getStatusCode().value());

        // The organization's audit trail records the administration.
        ResponseEntity<List> audit = http().getJson(
                "/api/v1/organizations/" + orgId + "/audit-events", List.class, Http.bearer(adminToken));
        assertEquals(200, audit.getStatusCode().value());
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "organization.member.add".equals(((Map<?, ?>) e).get("action"))));
    }

    private Map<String, Object> findByReportId(List<?> rows, String reportId) {
        return (Map<String, Object>) rows.stream()
                .filter(r -> reportId.equals(((Map<?, ?>) r).get("reportId")))
                .findFirst().orElseThrow();
    }
}
