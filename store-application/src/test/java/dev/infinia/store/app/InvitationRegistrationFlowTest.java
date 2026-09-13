package dev.infinia.store.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invitation-only registration (需求：邀请注册开关): while the switch is on,
 * /auth/register refuses sign-ups without a valid unused invitation code.
 * Sharing requires effective Level 2+ under a monthly quota (L2: 2, L3: 5,
 * L4: 10); platform admins issue without limit. Codes are single-use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InvitationRegistrationFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.IdentityRepositories.UserRepository users;

    @AfterEach
    void resetPolicy() {
        String admin = login("admin@infinia.local");
        assertEquals(HttpStatus.OK, setPolicy(admin, false).getStatusCode());
    }

    @Test
    void registrationGatesBehindTheSwitch() {
        // The policy is public (the sign-up form needs it) and off by default.
        ResponseEntity<Map> policy = http().getJson("/api/v1/auth/registration-policy",
                Map.class, null);
        assertEquals(HttpStatus.OK, policy.getStatusCode());
        assertEquals(Boolean.FALSE, policy.getBody().get("invitationRequired"));

        // Off: open registration works without a code.
        assertEquals(HttpStatus.CREATED, register(uniqueEmail("open"), null).getStatusCode());

        // On: a code becomes mandatory and must be a real unused one.
        String admin = login("admin@infinia.local");
        ResponseEntity<Map> flipped = setPolicy(admin, true);
        assertEquals(HttpStatus.OK, flipped.getStatusCode());
        assertEquals(Boolean.TRUE, flipped.getBody().get("invitationRequired"));

        ResponseEntity<Map> noCode = register(uniqueEmail("noc"), null);
        assertEquals(HttpStatus.BAD_REQUEST, noCode.getStatusCode());
        assertEquals("invitation_required", problemCode(noCode));

        ResponseEntity<Map> badCode = register(uniqueEmail("bad"), "NOPE1234567890");
        assertEquals(HttpStatus.BAD_REQUEST, badCode.getStatusCode());
        assertEquals("invitation_invalid", problemCode(badCode));

        // Off again: open registration is restored.
        setPolicy(admin, false);
        assertEquals(HttpStatus.CREATED, register(uniqueEmail("again"), null).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotaFollowsEffectiveLevelAndExhausts() {
        String admin = login("admin@infinia.local");

        // A fresh account below Level 2 cannot share codes.
        String email = uniqueEmail("quota");
        assertEquals(HttpStatus.CREATED, register(email, null).getStatusCode());
        String token = login(email);
        ResponseEntity<Map> denied = issue(token);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("invitation_level_required", problemCode(denied));

        // Level 2: exactly two codes per calendar month.
        setBeeLevel(admin, email, 2);
        assertEquals(HttpStatus.CREATED, issue(token).getStatusCode());
        assertEquals(HttpStatus.CREATED, issue(token).getStatusCode());
        ResponseEntity<Map> exhausted = issue(token);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exhausted.getStatusCode());
        assertEquals("invitation_quota_exceeded", problemCode(exhausted));

        Map<String, Object> mine = mine(token);
        assertEquals(Boolean.FALSE, mine.get("unlimited"));
        assertEquals(2, ((Number) mine.get("monthlyLimit")).intValue());
        assertEquals(2, ((Number) mine.get("issuedThisMonth")).intValue());
        assertEquals(2, ((List<?>) mine.get("invitations")).size());

        // Levels 3 and 4 widen the monthly allowance.
        setBeeLevel(admin, email, 3);
        assertEquals(5, ((Number) mine(token).get("monthlyLimit")).intValue());
        assertEquals(HttpStatus.CREATED, issue(token).getStatusCode()); // 3 of 5

        setBeeLevel(admin, email, 4);
        assertEquals(10, ((Number) mine(token).get("monthlyLimit")).intValue());
        assertEquals(HttpStatus.CREATED, issue(token).getStatusCode()); // 4 of 10

        // Platform admins share without limit on the member path too.
        assertEquals(HttpStatus.CREATED, issue(admin).getStatusCode());
        Map<String, Object> adminMine = mine(admin);
        assertEquals(Boolean.TRUE, adminMine.get("unlimited"));
        assertTrue(((List<?>) adminMine.get("invitations")).size() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminIssuesWithoutLimitAndCodesRegisterExactlyOnce() {
        String admin = login("admin@infinia.local");
        assertEquals(HttpStatus.OK, setPolicy(admin, true).getStatusCode());

        // The console issues without a level gate or monthly limit — 12 codes
        // is already beyond the Level-4 quota of 10.
        String captured = null;
        for (int i = 0; i < 12; i++) {
            ResponseEntity<Map> issued = http().exchangeJson(HttpMethod.POST,
                    "/api/v1/admin/invitations", json(admin), Map.of(), Map.class);
            assertEquals(HttpStatus.CREATED, issued.getStatusCode());
            if (captured == null) {
                captured = (String) issued.getBody().get("code");
            }
        }
        assertNotNull(captured);
        final String firstCode = captured;

        // The ledger shows the codes with issuer emails, all still unused.
        ResponseEntity<List> ledger = http().getJson("/api/v1/admin/invitations",
                List.class, Http.bearer(admin));
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        Map<String, Object> row = (Map<String, Object>) ledger.getBody().stream()
                .filter(c -> firstCode.equals(((Map<?, ?>) c).get("code")))
                .findFirst().orElseThrow();
        assertEquals("admin@infinia.local", row.get("createdByEmail"));
        assertNull(row.get("usedBy"));

        // A valid code registers exactly one account; the claim is recorded.
        String invited = uniqueEmail("invited");
        ResponseEntity<Map> redeemed = register(invited, firstCode);
        assertEquals(HttpStatus.CREATED, redeemed.getStatusCode());

        ResponseEntity<List> claimed = http().getJson("/api/v1/admin/invitations",
                List.class, Http.bearer(admin));
        Map<String, Object> claimedRow = (Map<String, Object>) claimed.getBody().stream()
                .filter(c -> firstCode.equals(((Map<?, ?>) c).get("code")))
                .findFirst().orElseThrow();
        assertNotNull(claimedRow.get("usedBy"));
        assertEquals(invited, claimedRow.get("usedByEmail"));
        assertNotNull(claimedRow.get("usedAt"));

        // The same code cannot register a second account.
        ResponseEntity<Map> replay = register(uniqueEmail("replay"), firstCode);
        assertEquals(HttpStatus.BAD_REQUEST, replay.getStatusCode());
        assertEquals("invitation_invalid", problemCode(replay));

        // Non-admins never reach the console.
        String user = login("user@infinia.local");
        assertEquals(HttpStatus.FORBIDDEN, http().getJson("/api/v1/admin/invitations",
                List.class, Http.bearer(user)).getStatusCode());
    }

    // ---- helpers ----

    private Http http() {
        return new Http(port);
    }

    private String login(String email) {
        return AuthTestSupport.login(http(), null, email,
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
    }

    private static HttpHeaders json(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@infinia.local";
    }

    private ResponseEntity<Map> register(String email, String invitationCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        body.put("displayName", "Invitation Flow");
        if (invitationCode != null) {
            body.put("invitationCode", invitationCode);
        }
        return http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register", json(null),
                body, Map.class);
    }

    private ResponseEntity<Map> setPolicy(String admin, boolean required) {
        return http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/invitations/settings",
                json(admin), Map.of("invitationRequired", required), Map.class);
    }

    private void setBeeLevel(String admin, String email, int level) {
        UUID target = users.findByEmailNormalized(email).orElseThrow().id;
        assertEquals(HttpStatus.OK, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/users/" + target, json(admin), Map.of("beeLevel", level),
                Map.class).getStatusCode());
    }

    private ResponseEntity<Map> issue(String token) {
        return http().exchangeJson(HttpMethod.POST, "/api/v1/invitations", json(token),
                Map.of(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mine(String token) {
        ResponseEntity<Map> response = http().getJson("/api/v1/invitations/mine", Map.class,
                Http.bearer(token));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private static String problemCode(ResponseEntity<Map> response) {
        return (String) response.getBody().get("code");
    }
}
