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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Desktop long-lived sessions (design §7.2): the public OAuth client's rotating
 * refresh credential — issue, single-use refresh with rotation, replay → family
 * revocation, session-revocation cascade, uniform rejection. The rate limit is
 * lifted here so the shared window cannot bleed across tests; the dedicated
 * rate-limit test class pins it low instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "store.refresh.rate-limit-per-minute=10000")
@ActiveProfiles("test")
class DesktopSessionFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.infrastructure.persistence.repository.RefreshTokenJpaRepository refreshRows;

    Http http() {
        return new Http(port);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    /** Full desktop sign-in + one credential issue on the fresh session. */
    private record DesktopSignIn(String accessToken, String refreshToken) {}

    private DesktopSignIn signIn() {
        AuthTestSupport.OAuthGrant grant = AuthTestSupport.desktopLogin(http(),
                "user@infinia.local", dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        ResponseEntity<Map> issued = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/desktop-session", Http.bearerJson(grant.accessToken()),
                Map.of(), Map.class);
        assertEquals(200, issued.getStatusCode().value(), "body: " + issued.getBody());
        assertEquals("no-store", issued.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        return new DesktopSignIn(grant.accessToken(),
                (String) jsonBody(issued).get("refreshToken"));
    }

    private ResponseEntity<Map> refresh(String token) {
        return http().exchangeJson(HttpMethod.POST, "/api/v1/auth/refresh",
                jsonHeaders(null), Map.of("refreshToken", token), Map.class);
    }

    @Test
    void issuesRotatesAndTheNewAccessTokenWorks() {
        DesktopSignIn signIn = signIn();
        assertTrue(signIn.refreshToken().length() >= 43,
                "the credential carries 256 bits of entropy (43 base64url chars)");

        ResponseEntity<Map> refreshed = refresh(signIn.refreshToken());
        assertEquals(200, refreshed.getStatusCode().value(), "body: " + refreshed.getBody());
        Map<String, Object> grant = jsonBody(refreshed);
        assertNotEquals(signIn.refreshToken(), grant.get("refreshToken"),
                "rotation issues a new credential");
        assertTrue(((Number) grant.get("expiresIn")).longValue() > 0);
        assertEquals("no-store", refreshed.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));

        // The refreshed access token rides the SAME session — /me answers with it.
        ResponseEntity<Map> me = http().getJson("/api/v1/me", Map.class,
                Http.bearer((String) grant.get("accessToken")));
        assertEquals(200, me.getStatusCode().value());
        assertEquals("user@infinia.local", jsonBody(me).get("email"));
    }

    @Test
    void replayedCredentialRevokesTheWholeFamilyAndTheSession() {
        DesktopSignIn signIn = signIn();
        ResponseEntity<Map> first = refresh(signIn.refreshToken());
        assertEquals(200, first.getStatusCode().value());
        String rotated = (String) jsonBody(first).get("refreshToken");
        String rotatedAccess = (String) jsonBody(first).get("accessToken");

        // Replaying the CONSUMED credential is theft-style reuse: 401, and the
        // family plus the session die — even the rotated pair stops working.
        assertEquals(401, refresh(signIn.refreshToken()).getStatusCode().value());
        assertEquals(401, refresh(rotated).getStatusCode().value(),
                "the rotated credential died with the family");
        assertEquals(401, http().getJson("/api/v1/me", Map.class,
                Http.bearer(rotatedAccess)).getStatusCode().value(),
                "the session ledger row is revoked, killing refreshed access tokens");
    }

    @Test
    void revokedSessionKillsTheRefreshFamily() {
        DesktopSignIn signIn = signIn();
        // Revoke every OAUTH_TOKEN session of the demo user — this sign-in's
        // included (the security page is the user's remote logout).
        ResponseEntity<List> sessions = http().getJson("/api/v1/me/sessions", List.class,
                Http.bearer(signIn.accessToken()));
        jsonList(sessions).stream()
                .filter(s -> "OAUTH_TOKEN".equals(s.get("kind")))
                .forEach(s -> assertEquals(204, http().exchange(HttpMethod.DELETE,
                        "/api/v1/me/sessions/" + s.get("sessionId"),
                        Http.bearer(signIn.accessToken()), null)
                        .getStatusCode().value()));

        assertEquals(401, refresh(signIn.refreshToken()).getStatusCode().value(),
                "a session revoked from the security page can no longer renew");
    }

    @Test
    void expiredCredentialIsRejected() {
        DesktopSignIn signIn = signIn();
        // Age every still-active row past its sliding TTL directly in the ledger.
        refreshRows.findAll().stream()
                .filter(r -> r.consumedAt == null && !r.revoked)
                .forEach(r -> {
                    r.expiresAt = Instant.now().minusSeconds(60);
                    refreshRows.save(r);
                });
        assertEquals(401, refresh(signIn.refreshToken()).getStatusCode().value());
    }

    @Test
    void unknownTokensAreUniformlyRejected() {
        for (String garbage : List.of("totally-unknown", "", "   ")) {
            assertEquals(401, refresh(garbage).getStatusCode().value());
        }
    }

    @Test
    void signOutRevocationEndsTheFamily() {
        DesktopSignIn signIn = signIn();
        assertEquals(204, http().exchangeJson(HttpMethod.POST, "/api/v1/auth/revoke",
                jsonHeaders(null), Map.of("refreshToken", signIn.refreshToken()), Void.class)
                .getStatusCode().value());
        assertEquals(401, refresh(signIn.refreshToken()).getStatusCode().value());
        // Uniform 204 — a revoke of garbage neither errors nor confirms anything.
        assertEquals(204, http().exchangeJson(HttpMethod.POST, "/api/v1/auth/revoke",
                jsonHeaders(null), Map.of("refreshToken", "garbage"), Void.class)
                .getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> jsonList(ResponseEntity<List> response) {
        return (List<Map<String, Object>>) (List<?>) response.getBody();
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
