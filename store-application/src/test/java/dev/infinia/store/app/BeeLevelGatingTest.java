package dev.infinia.store.app;

import org.junit.jupiter.api.AfterEach;
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
 * Bee-level gating (需求：插件支持设定指定等级用户可查看下载): a listing with
 * {@code minBeeLevel > 0} disappears from catalogs and refuses detail,
 * resolution and delivery surfaces for viewers below the level (anonymous
 * included); sufficient bees and platform admins see everything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BeeLevelGatingTest {

    private static final String COORDINATE = "infinia://plugin/official/markdown";

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

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

    private UUID listingId() {
        return listings.findByCoordinate(dev.infinia.store.contract.coordinate.InfiniaCoordinate
                .parse(COORDINATE)).orElseThrow().id;
    }

    private void setGate(String adminToken, int level) {
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId() + "/min-bee-level", json(adminToken),
                Map.of("minBeeLevel", level), Map.class).getStatusCode().value());
    }

    private void setBeeLevel(String adminToken, String email, int level) {
        UUID target = users.findByEmailNormalized(email).orElseThrow().id;
        assertEquals(200, http().exchangeJson(HttpMethod.PUT, "/api/v1/admin/users/" + target,
                json(adminToken), Map.of("beeLevel", level), Map.class).getStatusCode().value());
    }

    @AfterEach
    void resetGateAndLevels() {
        dev.infinia.store.domain.model.Listing listing = listings.findById(listingId())
                .orElseThrow();
        listing.minBeeLevel = 0;
        listings.save(listing);
        users.findByEmailNormalized("user@infinia.local").ifPresent(u -> {
            u.beeLevel = 1; // seeded WORKER
            users.save(u);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalogPlugins(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        ResponseEntity<Map> page = http().getJson("/api/v1/catalog?type=PLUGIN", Map.class,
                headers);
        return (List<Map<String, Object>>) (List<?>) page.getBody().get("items");
    }

    private String publishedReleaseId() {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/markdown",
                Map.class, Http.bearer(login("admin@infinia.local")));
        List<Map<String, Object>> releases =
                (List<Map<String, Object>>) (List<?>) detail.getBody().get("releases");
        return releases.stream().filter(r -> "PUBLISHED".equals(r.get("status")))
                .map(r -> (String) r.get("releaseId")).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void gateHidesListingBelowLevelAndOpensAbove() {
        String admin = login("admin@infinia.local");
        String user = login("user@infinia.local"); // WORKER (1)
        String releaseId = publishedReleaseId();

        // Baseline: everyone sees the ungated listing, catalog rows carry the gate.
        assertTrue(catalogPlugins(null).stream().anyMatch(i ->
                COORDINATE.equals(i.get("coordinate"))));
        assertEquals(0, ((Number) catalogPlugins(null).stream()
                .filter(i -> COORDINATE.equals(i.get("coordinate"))).findFirst().orElseThrow()
                .get("minBeeLevel")).intValue());

        setGate(admin, 2);

        // Catalog: hidden for anonymous and for WORKER(1).
        assertTrue(catalogPlugins(null).stream().noneMatch(i ->
                COORDINATE.equals(i.get("coordinate"))), "anonymous cannot see gated listing");
        assertTrue(catalogPlugins(user).stream().noneMatch(i ->
                COORDINATE.equals(i.get("coordinate"))), "WORKER(1) below gate(2)");

        // Detail: explicit 403 with the structured bee_level_required problem.
        ResponseEntity<Map> deniedDetail = http().getJson("/api/v1/listings/official/markdown",
                Map.class, Http.bearer(user));
        assertEquals(403, deniedDetail.getStatusCode().value());
        assertEquals("bee_level_required", deniedDetail.getBody().get("code"));
        Map<String, Object> params = (Map<String, Object>) deniedDetail.getBody()
                .get("parameters");
        assertEquals(2, ((Number) params.get("requiredBeeLevel")).intValue());
        assertEquals(403, http().getJson("/api/v1/listings/official/markdown",
                Map.class, null).getStatusCode().value(), "anonymous detail denied too");

        // Ratings view is gated the same way.
        assertEquals(403, http().getJson("/api/v1/listings/official/markdown/ratings",
                Map.class, Http.bearer(user)).getStatusCode().value());

        // Delivery: no ticket, no checksums, no manifest, no resolution.
        assertEquals(403, http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/download-ticket", json(user), null,
                Map.class).getStatusCode().value());
        ResponseEntity<String> deniedChecksums = http().get(
                "/api/v1/releases/" + releaseId + "/checksums.txt", Http.bearer(user));
        assertEquals(403, deniedChecksums.getStatusCode().value());
        assertTrue(deniedChecksums.getBody().contains("bee_level_required"));
        assertEquals(403, http().getJson("/api/v1/releases/" + releaseId + "/install-manifest",
                Map.class, Http.bearer(user)).getStatusCode().value());
        ResponseEntity<Map> deniedResolve = http().exchangeJson(HttpMethod.POST,
                "/api/v1/resolutions", json(user), Map.of("coordinate", COORDINATE,
                        "client", Map.of("hostVersion", "4.1.0", "os", "macos",
                                "arch", "arm64", "channel", "stable", "installed", List.of())),
                Map.class);
        assertEquals(403, deniedResolve.getStatusCode().value());
        assertEquals("bee_level_required", deniedResolve.getBody().get("code"));

        // Platform admins bypass the ladder entirely.
        assertTrue(catalogPlugins(admin).stream().anyMatch(i ->
                COORDINATE.equals(i.get("coordinate"))));
        assertEquals(200, http().getJson("/api/v1/listings/official/markdown",
                Map.class, Http.bearer(admin)).getStatusCode().value());
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/download-ticket", json(admin), null,
                Map.class).getStatusCode().value());

        // Write surfaces are gated consistently: no rating, no favorite, no report
        // on a listing the viewer cannot even see.
        assertEquals(403, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/listings/official/markdown/ratings", json(user),
                Map.of("stars", 5, "comment", "great"), Map.class).getStatusCode().value());
        assertEquals(403, http().exchangeJson(HttpMethod.PUT,
                "/api/v1/me/favorites/" + listingId(), json(user), null, Map.class)
                .getStatusCode().value());
        assertEquals(403, http().exchangeJson(HttpMethod.POST, "/api/v1/reports", json(user),
                Map.of("coordinate", COORDINATE, "reason", "spam"), Map.class)
                .getStatusCode().value());

        // Anonymous compatibility surfaces expose nothing gated either: the
        // CLAUDE marketplace mirror and the compat catalogs.
        String marketplace = http().get("/api/v1/compat/fengyu/claude-marketplace.json",
                null).getBody();
        assertNotNull(marketplace);
        assertFalse(marketplace.contains("official.markdown"),
                "gated listing absent from the CLAUDE marketplace mirror");
        ResponseEntity<String> compatCatalog = http().get("/api/v1/compat/fengyu/catalog", null);
        assertFalse(compatCatalog.getBody().contains("official.markdown"),
                "gated listing absent from the FengYu plugin compat catalog");

        // Promote to FORAGER(2): view + download open up.
        setBeeLevel(admin, "user@infinia.local", 2);
        String promotedToken = login("user@infinia.local");
        assertTrue(catalogPlugins(promotedToken).stream().anyMatch(i ->
                COORDINATE.equals(i.get("coordinate"))));
        assertEquals(200, http().getJson("/api/v1/listings/official/markdown",
                Map.class, Http.bearer(promotedToken)).getStatusCode().value());
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/download-ticket", json(promotedToken), null,
                Map.class).getStatusCode().value());

        // Ungate restores anonymous visibility.
        setGate(admin, 0);
        assertTrue(catalogPlugins(null).stream().anyMatch(i ->
                COORDINATE.equals(i.get("coordinate"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publisherSetsGateAtCreationAndCanAdjustIt() {
        String publisher = login("publisher@infinia.local");
        String slug = "gated-creation-" + Long.toHexString(System.nanoTime());

        // At creation the publisher picks the hive level that may view/download.
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings", json(publisher), Map.of(
                        "namespace", "official", "slug", slug, "type", "PLUGIN",
                        "name", "VIP Plugin", "summary", "Only senior bees.",
                        "minBeeLevel", 3), Map.class);
        assertEquals(201, created.getStatusCode().value());
        assertEquals(3, ((Number) created.getBody().get("minBeeLevel")).intValue());

        // Out-of-range values are rejected with 400.
        assertEquals(400, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings", json(publisher), Map.of(
                        "namespace", "official", "slug", slug + "-x", "type", "PLUGIN",
                        "name", "Bad", "summary", "x", "minBeeLevel", 7), Map.class)
                .getStatusCode().value());

        // The owner adjusts the gate on their own published listing (markdown
        // ships a PUBLISHED release, so catalog visibility is observable).
        UUID markdownId = listingId();
        ResponseEntity<Map> gated = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + markdownId + "/min-bee-level", json(publisher),
                Map.of("minBeeLevel", 3), Map.class);
        assertEquals(200, gated.getStatusCode().value());
        assertEquals(3, ((Number) gated.getBody().get("minBeeLevel")).intValue());

        String user = login("user@infinia.local"); // WORKER (1)
        assertTrue(catalogPlugins(user).stream().noneMatch(i ->
                COORDINATE.equals(i.get("coordinate"))), "WORKER below gate(3)");
        String publisherAgain = login("publisher@infinia.local"); // GUARD (3)
        assertTrue(catalogPlugins(publisherAgain).stream().anyMatch(i ->
                COORDINATE.equals(i.get("coordinate"))), "GUARD meets gate(3)");

        // Non-owners cannot touch the gate.
        assertEquals(403, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + markdownId + "/min-bee-level", json(user),
                Map.of("minBeeLevel", 0), Map.class).getStatusCode().value());

        // The gate change is audited.
        String admin = login("admin@infinia.local");
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=50",
                List.class, Http.bearer(admin));
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "listing.minBeeLevel".equals(((Map<?, ?>) e).get("action"))));
    }
}
