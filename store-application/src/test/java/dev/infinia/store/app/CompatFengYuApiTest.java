package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The FengYu host consumes /api/v1/compat/fengyu/catalog through its built-in
 * FENGYU marketplace source type with zero code changes (design §2.1, §10.3).
 * These tests replay the host's exact request sequence: fetch the catalog JSON,
 * then GET downloadUrl and verify the bytes against the advertised sha256 — plus
 * the update-detection contract (catalog version must track the latest published
 * release).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CompatFengYuApiTest {

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    Http http() {
        return new Http(port);
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogServesSeededPluginWithWorkingDownloadUrl() throws Exception {
        ResponseEntity<List> catalog = http().getJson("/api/v1/compat/fengyu/catalog",
                List.class, null);
        assertEquals(200, catalog.getStatusCode().value());
        List<Map<String, Object>> entries = catalog.getBody();
        assertTrue(entries.size() >= 2, "seeded plugins present: " + entries.size());
        assertTrue(entries.stream().noneMatch(e -> "official.calendar".equals(e.get("id"))),
                "MCP listings must not leak into the plugin catalog");

        Map<String, Object> markdown = findByEntryId(entries, "official.markdown");
        assertEquals("2.4.0", markdown.get("version"));
        assertEquals("official", markdown.get("author"));
        assertEquals(false, markdown.get("official"),
                "host 'official' means FengYu-team shipped — never true for store publishers");
        assertNotNull(markdown.get("permissions"));
        String sha256 = (String) markdown.get("sha256");
        assertEquals(64, sha256.length());
        assertNull(markdown.get("signature"), "unsigned entries skip host sig checks");

        // The host does a plain GET on downloadUrl and sha256-verifies the bytes.
        String downloadUrl = (String) markdown.get("downloadUrl");
        assertTrue(downloadUrl.startsWith("http"), "absolute URL for host-side fetch");
        assertTrue(downloadUrl.contains("/api/v1/blobs/"));
        ResponseEntity<byte[]> body = http().getBytes(rewrite(downloadUrl));
        assertEquals(200, body.getStatusCode().value());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBody());
        assertEquals(sha256, HexFormat.of().formatHex(digest), "downloaded bytes match sha256");
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogVersionTracksNewlyPublishedRelease() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String slug = "compat-" + UUID.randomUUID().toString().substring(0, 8);

        // Publish 1.0.0 through the regular pipeline.
        publishPlugin(publisherToken, reviewerToken, slug, "1.0.0");
        Map<String, Object> entry = findByEntryId(
                http().<List>getJson("/api/v1/compat/fengyu/catalog", List.class, null).getBody(),
                slug + ".compat-tool");
        assertEquals("1.0.0", entry.get("version"));

        // A host that installed 1.0.0 sees the new version in the catalog
        // (updateAvailable = catalog version > installed manifest version).
        publishPlugin(publisherToken, reviewerToken, slug, "1.1.0");
        entry = findByEntryId(
                http().<List>getJson("/api/v1/compat/fengyu/catalog", List.class, null).getBody(),
                slug + ".compat-tool");
        assertEquals("1.1.0", entry.get("version"));
        String sha256 = (String) entry.get("sha256");
        assertEquals(64, sha256.length());
        ResponseEntity<byte[]> body = http().getBytes(rewrite((String) entry.get("downloadUrl")));
        assertEquals(200, body.getStatusCode().value());
        assertEquals(sha256,
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body.getBody())));
    }

    private void publishPlugin(String publisherToken, String reviewerToken, String slug,
            String version) throws Exception {
        http().exchangeJson(HttpMethod.POST, "/api/v1/organizations", jsonAuth(publisherToken),
                Map.of("slug", slug, "name", "Compat Org"), Map.class);
        http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings",
                jsonAuth(publisherToken), Map.of("namespace", slug, "slug", "compat-tool",
                        "type", "PLUGIN", "name", "Compat Tool", "summary", "s"), Map.class);
        String listingId = listings.findByCoordinate(
                        dev.infinia.store.contract.coordinate.InfiniaCoordinate.parse(
                                "infinia://plugin/" + slug + "/compat-tool"))
                .orElseThrow().id.toString();
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", version, "channel", "stable"), Map.class);
        assertEquals(201, release.getStatusCode().value());
        String releaseId = (String) release.getBody().get("releaseId");

        ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/uploads", jsonAuth(publisherToken),
                Map.of("filename", "compat-tool-" + version + ".fyp"), Map.class);
        String uploadUrl = (String) upload.getBody().get("uploadUrl");
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertEquals(204, http().exchange(HttpMethod.PUT, uploadUrl, putHeaders,
                PublishingPipelineTest.validPluginZip("compat." + slug + ".tool", version))
                .getStatusCode().value());

        assertEquals(202, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit",
                Http.bearer(publisherToken), null, Map.class).getStatusCode().value());
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");

        ResponseEntity<List> queue = http().getJson("/api/v1/reviews?status=IN_REVIEW",
                List.class, Http.bearer(reviewerToken));
        String reviewId = null;
        for (Object r : queue.getBody()) {
            Map<?, ?> review = (Map<?, ?>) r;
            if (releaseId.equals(review.get("releaseId"))) {
                reviewId = (String) review.get("reviewId");
            }
        }
        assertNotNull(reviewId, "release reached the review queue");
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/reviews/" + reviewId + "/decisions", jsonAuth(reviewerToken),
                Map.of("decision", "APPROVE"), Map.class).getStatusCode().value());
    }

    /** test profile resolves ${server.port} before the random port is bound */
    private String rewrite(String url) {
        return url.replaceFirst("http://[^/]+", http().base);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillsCatalogServesSeededSkillWithDownload() throws Exception {
        ResponseEntity<List> catalog = http().getJson("/api/v1/compat/fengyu/skills-catalog",
                List.class, null);
        assertEquals(200, catalog.getStatusCode().value());
        List<Map<String, Object>> entries = catalog.getBody();
        Map<String, Object> pdf = findByEntryId(entries, "official.pdf-tools");
        assertEquals("1.3.0", pdf.get("version"));
        assertEquals(false, pdf.get("official"), "seeded skill is not fan.summer. official");
        // Host skill install: GET downloadUrl, ≤10MB zip — no sha256 field consumed.
        ResponseEntity<byte[]> body = http().getBytes(rewrite((String) pdf.get("downloadUrl")));
        assertEquals(200, body.getStatusCode().value());
        assertTrue(body.getBody().length > 0 && body.getBody().length <= 10 * 1024 * 1024);
        assertEquals("PK", new String(body.getBody(), 0, 2, "UTF-8"), "zip magic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void portableUpdateMirrorServesSeededAppWithDigest() throws Exception {
        ResponseEntity<Map> release = http().getJson(
                "/api/v1/compat/fengyu/fengyu-releases/api/releases/latest"
                        + "?channel=windows-portable",
                Map.class, null);
        assertEquals(200, release.getStatusCode().value());
        // Seeded fengyu-host stable is 4.1.0 (4.2.0-beta.1 is a prerelease on beta).
        assertEquals("v4.1.0", release.getBody().get("tag_name"));
        List<Map<String, Object>> assets =
                (List<Map<String, Object>>) release.getBody().get("assets");
        assertEquals(1, assets.size());
        Map<String, Object> asset = assets.get(0);
        assertEquals("Infinia-4.1.0-win32-x64-portable.zip", asset.get("name"));
        String digest = (String) asset.get("digest");
        assertTrue(digest.matches("sha256:[0-9a-f]{64}"),
                "digest is mandatory on the proxy channel");

        ResponseEntity<byte[]> body = http().getBytes(rewrite((String) asset
                .get("browser_download_url")));
        assertEquals(200, body.getStatusCode().value());
        assertEquals(digest.substring("sha256:".length()),
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(body.getBody())));
    }

    private void awaitStatus(String token, String releaseId, String expected)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            ResponseEntity<Map> status = http().getJson(
                    "/api/v1/publisher/releases/" + releaseId, Map.class, Http.bearer(token));
            if (expected.equals(status.getBody().get("status"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Release never reached " + expected);
    }

    private static Map<String, Object> findByEntryId(List<Map<String, Object>> entries, String id) {
        return entries.stream().filter(e -> id.equals(e.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no catalog entry " + id));
    }
}
