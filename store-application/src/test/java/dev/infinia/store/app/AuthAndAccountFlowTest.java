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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account flows (design §7): registration, PKCE login, /me, sessions, favorites and
 * idempotent install telemetry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthAndAccountFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    Http http() {
        return new Http(port);
    }

    @Test
    void registersAndRejectsDuplicates() {
        Map<String, Object> body = Map.of("email", "new-user@example.com",
                "password", "Sup3rSecret!", "displayName", "New User");
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register",
                jsonHeaders(null), body, Map.class);
        assertEquals(201, created.getStatusCode().value());
        assertEquals("new-user@example.com", created.getBody().get("email"));
        assertEquals(List.of("USER"), created.getBody().get("roles"));

        ResponseEntity<Map> duplicate = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/register", jsonHeaders(null), body, Map.class);
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals("email_taken", duplicate.getBody().get("code"));
    }

    @Test
    void rejectsWeakPasswords() {
        Map<String, Object> body = Map.of("email", "weak@example.com", "password", "short");
        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/register", jsonHeaders(null), body, Map.class);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("validation_failed", response.getBody().get("code"));
    }

    @Test
    void localizedErrorsFollowAcceptLanguage() {
        Map<String, Object> body = Map.of("email", "weak2@example.com", "password", "short");
        HttpHeaders headers = jsonHeaders(null);
        headers.setAcceptLanguage(java.util.Locale.LanguageRange.parse("zh-CN"));
        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/register", headers, body, Map.class);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("校验失败", response.getBody().get("title"), "zh-CN errors are localized");
    }

    @Test
    void pkceLoginGrantsTokenAndMe() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        ResponseEntity<Map> me = http().getJson("/api/v1/me", Map.class, Http.bearer(token));
        assertEquals(200, me.getStatusCode().value());
        assertEquals("user@infinia.local", me.getBody().get("email"));
        assertTrue(((List<?>) me.getBody().get("roles")).contains("USER"));
    }

    @Test
    void unauthenticatedMeReturns401Problem() {
        ResponseEntity<Map> response = http().getJson("/api/v1/me", Map.class, null);
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void favoritesAppearInLibrary() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        UUID listingId = listings.findByCoordinate(
                        dev.infinia.store.contract.coordinate.InfiniaCoordinate.parse(
                                "infinia://plugin/official/markdown"))
                .orElseThrow().id;

        ResponseEntity<String> fav = http().exchange(HttpMethod.PUT,
                "/api/v1/me/favorites/" + listingId, Http.bearer(token), null);
        assertEquals(204, fav.getStatusCode().value());

        ResponseEntity<Map> library = http().getJson("/api/v1/me/library", Map.class,
                Http.bearer(token));
        List<Map<String, Object>> favorites =
                (List<Map<String, Object>>) library.getBody().get("favorites");
        assertTrue(favorites.stream().anyMatch(f -> "Markdown Tools".equals(f.get("name"))));

        ResponseEntity<String> unfav = http().exchange(HttpMethod.DELETE,
                "/api/v1/me/favorites/" + listingId, Http.bearer(token), null);
        assertEquals(204, unfav.getStatusCode().value());
    }

    @Test
    void sessionsCanBeListedAndRevoked() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        ResponseEntity<List> sessions = http().getJson("/api/v1/me/sessions", List.class,
                Http.bearer(token));
        assertEquals(200, sessions.getStatusCode().value());
        List<Map<String, Object>> body = sessions.getBody();
        assertTrue(body.size() >= 1);
        String sessionId = sidOf(token);

        ResponseEntity<String> revoke = http().exchange(HttpMethod.DELETE,
                "/api/v1/me/sessions/" + sessionId, Http.bearer(token), null);
        assertEquals(204, revoke.getStatusCode().value());

        // The revoked session's token no longer passes the resource server.
        ResponseEntity<Map> rejected = http().getJson("/api/v1/me", Map.class, Http.bearer(token));
        assertEquals(401, rejected.getStatusCode().value());
    }

    @Test
    void installEventsAreIdempotent() {
        String token = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String event = """
                [{"idempotencyKey":"evt-001","coordinate":"infinia://plugin/official/markdown",
                  "version":"2.4.0","type":"PLUGIN","action":"install","outcome":"success",
                  "hostVersion":"4.0.1","os":"macos","arch":"arm64"}]
                """;
        HttpHeaders headers = Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Integer> first = http().exchangeJson(HttpMethod.POST,
                "/api/v1/install-events", headers, event, Integer.class);
        assertEquals(202, first.getStatusCode().value());
        assertEquals(1, first.getBody());

        ResponseEntity<Integer> duplicate = http().exchangeJson(HttpMethod.POST,
                "/api/v1/install-events", headers, event, Integer.class);
        assertEquals(202, duplicate.getStatusCode().value());
        assertEquals(0, duplicate.getBody(), "duplicate idempotency key must be ignored");
    }

    /** Extracts the sid claim from the JWT so the test revokes its own session. */
    private static String sidOf(String token) {
        String payload = token.split("\\.")[1];
        payload += "=".repeat(-payload.length() % 4);
        String json = new String(java.util.Base64.getUrlDecoder().decode(payload));
        return java.util.regex.Pattern.compile("\"sid\":\"([^\"]+)\"")
                .matcher(json).results().findFirst().orElseThrow().group(1);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
