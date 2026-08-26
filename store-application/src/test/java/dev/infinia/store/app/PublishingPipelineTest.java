package dev.infinia.store.app;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full publishing loop (design §16 Phase 3 acceptance): organization + reserved
 * namespace → listing → draft release → presigned upload → submit → async scan →
 * review decision → published → visible anonymously. Includes automatic rejection
 * for malicious packages and the self-review guard.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublishingPipelineTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    Http http() {
        return new Http(port);
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void publishToCatalogEndToEnd() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                "Password123!");

        // 1. Organization + reserved namespace, owned by the CI account.
        String slug = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> org = http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(publisherToken), Map.of("slug", slug, "name", "E2E Org"), Map.class);
        assertEquals(201, org.getStatusCode().value());

        // 2. Listing.
        ResponseEntity<Map> listing = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings", jsonAuth(publisherToken), Map.of(
                        "namespace", slug, "slug", "demo-tool", "type", "PLUGIN",
                        "category", "Productivity", "tags", List.of("demo"),
                        "name", "Demo Tool", "summary", "Created by the e2e pipeline test"),
                Map.class);
        assertEquals(201, listing.getStatusCode().value());
        String listingId = listings.findByCoordinate(
                        InfiniaCoordinate.parse("infinia://plugin/" + slug + "/demo-tool"))
                .orElseThrow().id.toString();

        // 3. Draft release.
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", "1.0.0", "channel", "stable", "requiresHost", ">=4.0.0 <5.0.0",
                        "license", "MIT"), Map.class);
        assertEquals(201, release.getStatusCode().value());
        assertEquals("DRAFT", release.getBody().get("status"));
        String releaseId = (String) release.getBody().get("releaseId");

        // 4. Upload session -> presigned PUT -> bytes stored.
        ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/uploads", jsonAuth(publisherToken),
                Map.of("filename", "demo-tool-1.0.0.fyp"), Map.class);
        assertEquals(201, upload.getStatusCode().value());
        String uploadUrl = (String) upload.getBody().get("uploadUrl");
        assertTrue(uploadUrl.contains("sig="));
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<String> put = http().exchange(HttpMethod.PUT, uploadUrl, putHeaders,
                validPluginZip("demo-tool", "1.0.0"));
        assertEquals(204, put.getStatusCode().value());

        // 5. Submit -> async scan -> IN_REVIEW.
        ResponseEntity<Map> submit = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit",
                Http.bearer(publisherToken), null, Map.class);
        assertEquals(202, submit.getStatusCode().value());
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");

        // 6. Reviewer queue contains the release; approve it.
        ResponseEntity<List> queue = http().getJson("/api/v1/reviews?status=IN_REVIEW",
                List.class, Http.bearer(reviewerToken));
        assertEquals(200, queue.getStatusCode().value());
        Map<String, Object> queued = findReview(queue.getBody(), releaseId);

        ResponseEntity<Map> decision = http().exchangeJson(HttpMethod.POST,
                "/api/v1/reviews/" + queued.get("reviewId") + "/decisions",
                jsonAuth(reviewerToken), Map.of("decision", "APPROVE", "notes", "lgtm"),
                Map.class);
        assertEquals(200, decision.getStatusCode().value());
        assertEquals("APPROVED", decision.getBody().get("status"));

        // 7. Published, signed release visible anonymously.
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/" + slug + "/demo-tool",
                Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        Map<String, Object> published = releasesOf(detail.getBody()).get(0);
        assertEquals("PUBLISHED", published.get("status"));
        Map<String, Object> artifact = artifactsOf(published).get(0);
        assertNotNull(artifact.get("keyId"), "platform signature attached after review");
        assertEquals(64, ((String) artifact.get("sha256")).length());
    }

    @Test
    void maliciousPackageIsAutoRejected() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String slug = "evil-" + UUID.randomUUID().toString().substring(0, 8);
        http().exchangeJson(HttpMethod.POST, "/api/v1/organizations", jsonAuth(publisherToken),
                Map.of("slug", slug, "name", "Evil Org"), Map.class);
        http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings", jsonAuth(publisherToken),
                Map.of("namespace", slug, "slug", "evil-tool", "type", "PLUGIN", "name",
                        "Evil Tool", "summary", "s"), Map.class);
        String listingId = listings.findByCoordinate(
                        InfiniaCoordinate.parse("infinia://plugin/" + slug + "/evil-tool"))
                .orElseThrow().id.toString();
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", "0.1.0", "channel", "stable"), Map.class);
        String releaseId = (String) release.getBody().get("releaseId");
        String uploadUrl = uploadAndGetUrl(publisherToken, releaseId, "evil.fyp");
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        http().exchange(HttpMethod.PUT, uploadUrl, putHeaders, zipOf(new String[][] {
                {"plugin.json", "{\"id\":\"evil\",\"name\":\"Evil\",\"version\":\"0.1.0\"}"},
                {"config.json", "{\"key\": \"AKIAIOSFODNN7EXAMPLE\"}"}}));

        ResponseEntity<Map> submit = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit",
                Http.bearer(publisherToken), null, Map.class);
        assertEquals(202, submit.getStatusCode().value());

        Map<String, Object> status = awaitStatus(publisherToken, releaseId, "REJECTED");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) status.get("findings");
        assertTrue(findings.stream().anyMatch(f -> "secret.aws-access-key".equals(f.get("rule"))),
                "embedded AWS key must be flagged, findings: " + findings);
    }

    @Test
    void selfReviewIsForbidden() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String slug = "selfrev-" + UUID.randomUUID().toString().substring(0, 8);
        http().exchangeJson(HttpMethod.POST, "/api/v1/organizations", jsonAuth(publisherToken),
                Map.of("slug", slug, "name", "SelfRev"), Map.class);
        http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings", jsonAuth(publisherToken),
                Map.of("namespace", slug, "slug", "tool", "type", "SKILL", "name", "Tool",
                        "summary", "s"), Map.class);
        String listingId = listings.findByCoordinate(
                        InfiniaCoordinate.parse("infinia://skill/" + slug + "/tool"))
                .orElseThrow().id.toString();
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", "1.0.0", "channel", "stable"), Map.class);
        String releaseId = (String) release.getBody().get("releaseId");
        String uploadUrl = uploadAndGetUrl(publisherToken, releaseId, "tool.fys");
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        http().exchange(HttpMethod.PUT, uploadUrl, putHeaders, zipOf(new String[][] {{"SKILL.md",
                "---\nname: tool\ndescription: d\n---\n# Tool"}}));
        http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit",
                Http.bearer(publisherToken), null, Map.class);
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");

        ResponseEntity<List> queue = http().getJson("/api/v1/reviews?status=IN_REVIEW",
                List.class, Http.bearer(publisherToken));
        Map<String, Object> queued = findReview(queue.getBody(), releaseId);
        ResponseEntity<Map> decision = http().exchangeJson(HttpMethod.POST,
                "/api/v1/reviews/" + queued.get("reviewId") + "/decisions",
                jsonAuth(publisherToken), Map.of("decision", "APPROVE"), Map.class);
        assertEquals(403, decision.getStatusCode().value());
        assertEquals("self_review_forbidden", decision.getBody().get("code"));
    }

    // ---- helpers ----

    private String uploadAndGetUrl(String token, String releaseId, String filename) {
        ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/uploads", jsonAuth(token),
                Map.of("filename", filename), Map.class);
        assertEquals(201, upload.getStatusCode().value());
        return (String) upload.getBody().get("uploadUrl");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findReview(List<?> reviews, String releaseId) {
        for (Object r : reviews) {
            Map<String, Object> review = (Map<String, Object>) r;
            if (releaseId.equals(review.get("releaseId"))) {
                return review;
            }
        }
        throw new AssertionError("release " + releaseId + " not in review queue");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> releasesOf(Map<?, ?> detail) {
        return (List<Map<String, Object>>) detail.get("releases");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> artifactsOf(Map<?, ?> release) {
        return (List<Map<String, Object>>) release.get("artifacts");
    }

    private Map<String, Object> awaitStatus(String token, String releaseId, String expected)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            ResponseEntity<Map> status = http().getJson(
                    "/api/v1/publisher/releases/" + releaseId, Map.class, Http.bearer(token));
            if (expected.equals(status.getBody().get("status"))) {
                return status.getBody();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Release never reached " + expected);
    }

    static byte[] validPluginZip(String id, String version) throws Exception {
        return zipOf(new String[][] {
                {"plugin.json", "{\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"version\":\"" + version + "\",\"entry\":\"index.js\"}"},
                {"index.js", "export function run() { return 1; }"}});
    }

    static byte[] zipOf(String[][] entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String[] entry : entries) {
                zos.putNextEntry(new ZipEntry(entry[0]));
                zos.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
