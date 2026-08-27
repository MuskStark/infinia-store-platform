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

    /** Platform admins curate the whole catalog: they may list into namespaces they
     *  do not personally own (bug fix: admins previously hit namespace_not_owned). */
    @Test
    void platformAdminCanCreateListingsInForeignNamespaces() {
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                "Password123!");
        ResponseEntity<Map> listing = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings", jsonAuth(adminToken), Map.of(
                        "namespace", "official", "slug",
                        "admin-" + UUID.randomUUID().toString().substring(0, 8),
                        "type", "SKILL",
                        "category", "Productivity", "tags", List.of("admin"),
                        "name", "Admin Skill", "summary", "Created directly by the platform admin"),
                Map.class);
        assertEquals(201, listing.getStatusCode().value());
    }

    /** GET /publisher/listings/{id}/releases must include DRAFTs so the publisher
     *  center can resume an interrupted upload, and must stay owner-only. */
    @Test
    void listingReleasesIncludeDraftsAndStayOwnerOnly() {
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                "Password123!");
        String userToken = AuthTestSupport.login(http(), null, "user@infinia.local",
                "Password123!");
        String slug = "resume-" + UUID.randomUUID().toString().substring(0, 8);

        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(adminToken), Map.of("slug", slug, "name", "Resume Org"), Map.class)
                .getStatusCode().value());
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings",
                jsonAuth(adminToken), Map.of("namespace", slug, "slug", "tool", "type",
                        "PLUGIN", "category", "Productivity",
                        "name", "Resume Tool", "summary", "draft resume test"), Map.class)
                .getStatusCode().value());
        String listingId = listings.findByCoordinate(
                        InfiniaCoordinate.parse("infinia://plugin/" + slug + "/tool"))
                .orElseThrow().id.toString();

        // Owner creates a draft release, then reloads the listing releases.
        assertEquals(201, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(adminToken),
                Map.of("version", "0.1.0", "channel", "stable"), Map.class)
                .getStatusCode().value());

        ResponseEntity<List> own = http().exchangeJson(HttpMethod.GET,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(adminToken),
                null, List.class);
        assertEquals(200, own.getStatusCode().value());
        assertEquals(1, own.getBody().size());
        assertEquals("DRAFT", ((Map<?, ?>) own.getBody().get(0)).get("status"));
        assertEquals("0.1.0", ((Map<?, ?>) own.getBody().get(0)).get("version"));

        // A different plain user must not read another publisher's releases.
        assertEquals(403, http().exchangeJson(HttpMethod.GET,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(userToken),
                null, List.class).getStatusCode().value());
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

        // 8. Permissions come from the scanned package manifest (design §8.2 step 6),
        // not the publisher's claim — resolvers and host confirms read these.
        List<Map<String, Object>> permissions =
                (List<Map<String, Object>>) published.get("permissions");
        assertTrue(permissions.stream().anyMatch(p ->
                        "files.read".equals(p.get("permissionId"))),
                "package-declared permission surfaced: " + permissions);
    }

    @Test
    void binaryUploadWithFormContentTypeIsAccepted() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String slug = "formct-" + UUID.randomUUID().toString().substring(0, 8);
        http().exchangeJson(HttpMethod.POST, "/api/v1/organizations", jsonAuth(publisherToken),
                Map.of("slug", slug, "name", "FormCT"), Map.class);
        http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings", jsonAuth(publisherToken),
                Map.of("namespace", slug, "slug", "tool", "type", "PLUGIN", "name", "Tool",
                        "summary", "s"), Map.class);
        String listingId = listings.findByCoordinate(
                        InfiniaCoordinate.parse("infinia://plugin/" + slug + "/tool"))
                .orElseThrow().id.toString();
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", "1.0.0", "channel", "stable"), Map.class);
        String releaseId = (String) release.getBody().get("releaseId");
        String uploadUrl = uploadAndGetUrl(publisherToken, releaseId, "tool-1.0.0.fyp");

        // curl --data-binary defaults to application/x-www-form-urlencoded; the bytes
        // are still the client's intended payload — form parsing must not explode.
        HttpHeaders formHeaders = new HttpHeaders();
        formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> put = http().exchange(HttpMethod.PUT, uploadUrl, formHeaders,
                validPluginZip("formct." + slug + ".tool", "1.0.0"));
        assertEquals(204, put.getStatusCode().value(),
                "binary body with form content-type is accepted");
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
                {"manifest.json", "{\"schemaVersion\":2,\"id\":\"evil.tool\",\"name\":\"Evil\","
                        + "\"description\":\"d\",\"author\":\"e2e\",\"icon\":\"puzzle\","
                        + "\"category\":\"Productivity\",\"version\":\"0.1.0\","
                        + "\"ui\":{\"entry\":\"index.js\"},\"permissions\":[]}"},
                {"index.js", "export function run() { return 1; }"},
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
        http().exchange(HttpMethod.PUT, uploadUrl, putHeaders, validSkillZip(
                slug + ".tool", "1.0.0"));
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

    /** Host-contract .fyp: schemaVersion 2, full required fields, ui.entry,
     * string permissions from the allowlist, host-compatible engine range. */
    static byte[] validPluginZip(String id, String version) throws Exception {
        return zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":2,\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"description\":\"demo\",\"author\":\"e2e\","
                        + "\"icon\":\"puzzle\",\"category\":\"Productivity\","
                        + "\"version\":\"" + version + "\",\"ui\":{\"entry\":\"index.js\"},"
                        + "\"permissions\":[\"files.read\"],"
                        + "\"engines\":{\"fengyu\":\">=4.0.0 <5.0.0\"}}"},
                {"index.js", "export function run() { return 1; }"}});
    }

    /** Host-contract .fys: manifest.json (SkillManifest) plus SKILL.md at the root. */
    static byte[] validSkillZip(String id, String version) throws Exception {
        return zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id
                        + "\",\"description\":\"demo skill\",\"version\":\"" + version
                        + "\",\"author\":\"e2e\",\"official\":false}"},
                {"SKILL.md", "---\nname: " + id + "\ndescription: demo\n---\n# " + id}});
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
