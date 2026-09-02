package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Platform-admin user management (需求：商城管理员用户管理): list every account,
 * adjust bee levels (蜜蜂等级), disable/enable accounts with live session
 * revocation — with self-lockout protection and a full audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminUserManagementTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.IdentityRepositories.UserRepository users;

    Http http() {
        return new Http(port);
    }

    private String login(String email) {
        return AuthTestSupport.login(http(), null, email,
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
    }

    private HttpHeaders json(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String userId(String email) {
        return users.findByEmailNormalized(email).orElseThrow().id.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminListsEveryAccountWithBeeLevel() {
        String admin = login("admin@infinia.local");

        ResponseEntity<List> page = http().getJson("/api/v1/admin/users", List.class,
                Http.bearer(admin));
        assertEquals(200, page.getStatusCode().value());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) page.getBody();
        assertTrue(rows.size() >= 5, "seeded accounts are all listed");
        Map<String, Object> demo = rows.stream()
                .filter(r -> "user@infinia.local".equals(r.get("email")))
                .findFirst().orElseThrow();
        assertEquals(1, ((Number) demo.get("beeLevel")).intValue(), "demo user seeds as WORKER");
        assertEquals("ACTIVE", demo.get("status"));
        assertTrue(((List<String>) demo.get("roles")).contains("USER"));
        assertNotNull(demo.get("createdAt"));
    }

    @Test
    void nonAdminsCannotManageUsers() {
        String user = login("user@infinia.local");
        assertEquals(403, http().exchangeJson(HttpMethod.GET, "/api/v1/admin/users",
                Http.bearer(user), null, Map.class).getStatusCode().value());
        assertEquals(403, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + userId("user@infinia.local"), json(user),
                Map.of("beeLevel", 4), Map.class).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminAdjustsBeeLevelWithinLadderAndAudits() {
        String admin = login("admin@infinia.local");
        String target = userId("user@infinia.local");

        ResponseEntity<Map> updated = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + target, json(admin), Map.of("beeLevel", 3), Map.class);
        assertEquals(200, updated.getStatusCode().value());
        assertEquals(3, ((Number) updated.getBody().get("beeLevel")).intValue());
        assertEquals(3, users.findById(java.util.UUID.fromString(target)).orElseThrow().beeLevel);

        // Out-of-range levels are rejected.
        assertEquals(400, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + target, json(admin), Map.of("beeLevel", 9),
                Map.class).getStatusCode().value());

        // The promotion lands in the audit trail.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=50",
                List.class, Http.bearer(admin));
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "user.beeLevel".equals(((Map<?, ?>) e).get("action"))));

        // Restore for other tests.
        http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/users/" + target, json(admin),
                Map.of("beeLevel", 1), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disablingUserBlocksLoginAndRevokesSessions() {
        String admin = login("admin@infinia.local");

        // Fresh account so seeded users stay untouched.
        HttpHeaders register = new HttpHeaders();
        register.setContentType(MediaType.APPLICATION_JSON);
        String email = "gated-victim-" + System.nanoTime() + "@example.com";
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register",
                register, Map.of("email", email, "password", "Password123!",
                        "displayName", "Victim"), Map.class).getStatusCode().value());

        String token = login(email);
        assertEquals(200, http().getJson("/api/v1/me", Map.class,
                Http.bearer(token)).getStatusCode().value());

        String victimId = userId(email);
        ResponseEntity<Map> disabled = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + victimId, json(admin),
                Map.of("status", "DISABLED"), Map.class);
        assertEquals(200, disabled.getStatusCode().value());
        assertEquals("DISABLED", disabled.getBody().get("status"));

        // The live session was revoked: the token stops working immediately.
        assertEquals(401, http().getJson("/api/v1/me", Map.class,
                Http.bearer(token)).getStatusCode().value());

        // Direct login is refused while disabled.
        HttpHeaders form = new HttpHeaders();
        form.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> blocked = http().exchange(HttpMethod.POST,
                "/api/v1/auth/login", json(null),
                "{\"email\":\"" + email + "\",\"password\":\"Password123!\"}");
        assertEquals(403, blocked.getStatusCode().value());

        // Re-enable restores login.
        assertEquals(200, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + victimId, json(admin), Map.of("status", "ACTIVE"),
                Map.class).getStatusCode().value());
        assertNotNull(login(email));
    }

    @Test
    void adminCannotDisableSelfOrDropOwnAdminRole() {
        String admin = login("admin@infinia.local");
        String self = userId("admin@infinia.local");

        assertEquals(400, http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/users/" + self,
                json(admin), Map.of("status", "DISABLED"), Map.class)
                .getStatusCode().value());
        assertEquals(400, http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/users/" + self,
                json(admin), Map.of("roles", List.of("USER")), Map.class)
                .getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminCanGrantRoles() {
        String admin = login("admin@infinia.local");
        String target = userId("user@infinia.local");

        ResponseEntity<Map> updated = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + target, json(admin),
                Map.of("roles", List.of("USER", "PUBLISHER")), Map.class);
        assertEquals(200, updated.getStatusCode().value());
        List<String> roles = (List<String>) updated.getBody().get("roles");
        assertTrue(roles.contains("PUBLISHER") && roles.contains("USER"));

        // Unknown roles are rejected.
        assertEquals(400, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + target, json(admin),
                Map.of("roles", List.of("WIZARD")), Map.class).getStatusCode().value());

        // Restore.
        http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/users/" + target, json(admin),
                Map.of("roles", List.of("USER")), Map.class);
    }

    @Test
    void publicUserPayloadCarriesBeeLevel() {
        HttpHeaders register = new HttpHeaders();
        register.setContentType(MediaType.APPLICATION_JSON);
        String email = "newbee-" + System.nanoTime() + "@example.com";
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/register", register,
                Map.of("email", email, "password", "Password123!"), Map.class);
        assertEquals(201, created.getStatusCode().value());
        assertEquals(0, ((Number) created.getBody().get("beeLevel")).intValue(),
                "fresh accounts start as LARVA (0)");
    }

    @Test
    void lastLoginIsTrackedOnDirectLogin() {
        login("user@infinia.local");
        var user = users.findByEmailNormalized("user@infinia.local").orElseThrow();
        assertNotNull(user.lastLoginAt, "authenticate() records lastLoginAt");
        assertTrue(user.lastLoginAt.isAfter(
                java.time.Instant.now().minusSeconds(60)));
    }

    @Test
    void listUsersSortedOldestFirst() {
        String admin = login("admin@infinia.local");
        ResponseEntity<List> page = http().getJson("/api/v1/admin/users", List.class,
                Http.bearer(admin));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) page.getBody();
        for (int i = 1; i < rows.size(); i++) {
            String prev = (String) rows.get(i - 1).get("createdAt");
            String next = (String) rows.get(i).get("createdAt");
            assertTrue(prev.compareTo(next) <= 0, "createdAt ascending");
        }
    }
}
