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
 * Platform-admin curation (design §12.4 管理): every listing can be delisted
 * (UNLISTED — hidden from all catalogs) and editorially featured (drives the
 * store's featured shelf). All actions are admin-only and audited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ListingCurationTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    Http http() {
        return new Http(port);
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalogItems(String type) {
        return (List<Map<String, Object>>) http()
                .getJson("/api/v1/catalog?type=" + type, Map.class, null).getBody()
                .get("items");
    }

    private String login(String email) {
        return AuthTestSupport.login(http(), null, email,
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminCanDelistAndRelistListings() {
        String admin = login("admin@infinia.local");
        UUID listingId = listings.findByCoordinate(dev.infinia.store.contract.coordinate
                        .InfiniaCoordinate.parse("infinia://plugin/official/markdown"))
                .orElseThrow().id;

        // Non-admins cannot touch curation.
        String user = login("user@infinia.local");
        assertEquals(403, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/visibility", jsonAuth(user),
                Map.of("visibility", "UNLISTED"), Map.class).getStatusCode().value());

        // Delist → gone from every catalog surface.
        ResponseEntity<Map> delisted = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/visibility", jsonAuth(admin),
                Map.of("visibility", "UNLISTED"), Map.class);
        assertEquals(200, delisted.getStatusCode().value());
        assertEquals("UNLISTED", delisted.getBody().get("visibility"));

        List<Map<String, Object>> after = catalogItems("PLUGIN");
        assertTrue(after.stream().noneMatch(i ->
                        "infinia://plugin/official/markdown"
                                .equals(i.get("coordinate"))),
                "delisted listing disappears from the catalog");

        // Relist → visible again.
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/visibility", jsonAuth(admin),
                Map.of("visibility", "PUBLIC"), Map.class).getStatusCode().value());
        after = catalogItems("PLUGIN");
        assertTrue(after.stream().anyMatch(i ->
                "infinia://plugin/official/markdown".equals(i.get("coordinate"))));

        // Both actions are in the audit trail.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events",
                List.class, Http.bearer(admin));
        assertTrue(audit.getBody().stream().anyMatch(e ->
                "listing.visibility".equals(((Map<?, ?>) e).get("action"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminCanFeatureListingsAndCatalogFilters() {
        String admin = login("admin@infinia.local");
        UUID listingId = listings.findByCoordinate(dev.infinia.store.contract.coordinate
                        .InfiniaCoordinate.parse("infinia://plugin/official/email"))
                .orElseThrow().id;

        ResponseEntity<Map> featured = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/featured", jsonAuth(admin),
                Map.of("featured", true), Map.class);
        assertEquals(200, featured.getStatusCode().value());
        assertEquals(true, featured.getBody().get("featured"));

        // Featured shelf returns exactly the featured listing; the plain catalog
        // carries the flag too.
        ResponseEntity<Map> shelf = http().getJson("/api/v1/catalog?featured=true",
                Map.class, null);
        List<Map<String, Object>> shelfItems =
                (List<Map<String, Object>>) shelf.getBody().get("items");
        assertEquals(1, shelfItems.size());
        assertEquals("infinia://plugin/official/email", shelfItems.get(0).get("coordinate"));
        assertEquals(true, shelfItems.get(0).get("featured"));

        // Admin console sees every listing with curation state.
        ResponseEntity<List> console = http().getJson("/api/v1/admin/listings",
                List.class, Http.bearer(admin));
        assertTrue(console.getBody().stream().anyMatch(l ->
                "infinia://plugin/official/email".equals(((Map<?, ?>) l).get("coordinate"))
                        && Boolean.TRUE.equals(((Map<?, ?>) l).get("featured"))));

        // Unfeature cleans the shelf.
        http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingId + "/featured", jsonAuth(admin),
                Map.of("featured", false), Map.class);
        assertTrue(((List<?>) http().getJson("/api/v1/catalog?featured=true", Map.class,
                null).getBody().get("items")).isEmpty());
    }
}
