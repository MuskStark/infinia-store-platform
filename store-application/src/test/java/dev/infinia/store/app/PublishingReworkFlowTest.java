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
 * Publishing state-machine hardening (audit P1-2, P1-3, P2-9):
 *
 * <ul>
 *   <li>P1-2 — a pending upload session may not complete after submit: unscanned
 *       artifacts must never enter the review/publish chain.</li>
 *   <li>P1-3 — CHANGES_REQUESTED / REJECTED are recoverable: re-uploading the same
 *       route replaces the artifact and returns the release to the editing path,
 *       so it can be submitted again (previously: duplicate-key 500, dead end).</li>
 *   <li>P2-9 — the duplicate-version gate matches the DB unique index across ALL
 *       statuses and also rejects build-metadata-only variants.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PublishingReworkFlowTest {

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
    void pendingUploadSessionCannotCompleteAfterSubmit() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String listingId = newListing(publisherToken, "void-");
        String releaseId = createDraft(publisherToken, listingId, "1.0.0");

        // Session A is created while the release is a DRAFT, but never uploaded.
        String staleUrl = uploadSessionUrl(publisherToken, releaseId, "stale.fyp");

        // A second artifact is uploaded and the release is submitted.
        String liveUrl = uploadSessionUrl(publisherToken, releaseId, "live.fyp");
        putBytes(liveUrl, PublishingPipelineTest.validPluginZip("void.live", "1.0.0"));
        submit(publisherToken, releaseId);
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");

        // Completing the stale session now must be rejected (409) — its bytes
        // would bypass the scan that already ran (audit P1-2).
        ResponseEntity<String> latePut = http().exchange(HttpMethod.PUT, staleUrl, octetStream(),
                PublishingPipelineTest.validPluginZip("void.stale", "1.0.0"));
        assertEquals(409, latePut.getStatusCode().value(),
                "late upload must be rejected, got " + latePut.getBody());
        assertTrue(latePut.getBody().contains("invalid_state_transition")
                        || latePut.getBody().contains("voided"),
                "clear error code, got: " + latePut.getBody());

        // The release still carries exactly the one scanned artifact.
        ResponseEntity<Map> status = http().getJson("/api/v1/publisher/releases/" + releaseId,
                Map.class, Http.bearer(publisherToken));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) status.getBody()
                .get("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals("live.fyp", artifacts.get(0).get("filename"));
    }

    @Test
    void changesRequestedReworkReplacesArtifactAndAllowsResubmit() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                "Password123!");
        String listingId = newListing(publisherToken, "rework-");
        String releaseId = createDraft(publisherToken, listingId, "1.0.0");

        String firstUrl = uploadSessionUrl(publisherToken, releaseId, "tool.fyp");
        putBytes(firstUrl, PublishingPipelineTest.validPluginZip("rework.tool", "1.0.0"));
        submit(publisherToken, releaseId);
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");
        decide(publisherToken, reviewerToken, releaseId, "REQUEST_CHANGES");
        awaitStatus(publisherToken, releaseId, "CHANGES_REQUESTED");

        // The reviewer asked for changes; the publisher re-uploads the SAME route.
        // Old behavior: DataIntegrityViolationException → 500 with no artifact
        // delete API. New behavior: replacement + back on the editing path.
        String reworkUrl = uploadSessionUrl(publisherToken, releaseId, "tool.fyp");
        putBytes(reworkUrl, PublishingPipelineTest.validPluginZip("rework.tool", "1.0.0"));
        assertEquals("UPLOADING",
                awaitStatus(publisherToken, releaseId, "UPLOADING").get("status"));

        // One artifact on the route, not two.
        ResponseEntity<Map> status = http().getJson("/api/v1/publisher/releases/" + releaseId,
                Map.class, Http.bearer(publisherToken));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) status.getBody()
                .get("artifacts");
        assertEquals(1, artifacts.size());

        // And the reworked release can be submitted again.
        submit(publisherToken, releaseId);
        assertEquals("IN_REVIEW", awaitStatus(publisherToken, releaseId, "IN_REVIEW")
                .get("status"));
        decide(publisherToken, reviewerToken, releaseId, "APPROVE");
        assertEquals("PUBLISHED", awaitStatus(publisherToken, releaseId, "PUBLISHED")
                .get("status"));
    }

    @Test
    void rejectedReleaseCanBeReworkedAndResubmitted() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                "Password123!");
        String listingId = newListing(publisherToken, "rejrework-");
        String releaseId = createDraft(publisherToken, listingId, "1.0.0");

        String url = uploadSessionUrl(publisherToken, releaseId, "tool.fyp");
        putBytes(url, PublishingPipelineTest.validPluginZip("rejrework.tool", "1.0.0"));
        submit(publisherToken, releaseId);
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");
        decide(publisherToken, reviewerToken, releaseId, "REJECT");
        awaitStatus(publisherToken, releaseId, "REJECTED");

        // Re-uploading after REJECT moves the release back into the editing path
        // (explicit REJECTED→DRAFT→UPLOADING conversion) instead of wedging it.
        String reworkUrl = uploadSessionUrl(publisherToken, releaseId, "tool.fyp");
        putBytes(reworkUrl, PublishingPipelineTest.validPluginZip("rejrework.tool", "1.0.0"));
        awaitStatus(publisherToken, releaseId, "UPLOADING");
        submit(publisherToken, releaseId);
        assertEquals("IN_REVIEW", awaitStatus(publisherToken, releaseId, "IN_REVIEW")
                .get("status"));
    }

    @Test
    void duplicateVersionGateMatchesDatabaseAcrossStatusesAndBuildMetadata() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                "Password123!");
        String listingId = newListing(publisherToken, "dupe-");

        // Publish + yank 1.0.0, then try to re-create it: the DB unique index
        // holds the slot for every status, so the app check must reject with 409
        // (not pass and later die on a 500 constraint violation).
        String releaseId = createDraft(publisherToken, listingId, "1.0.0");
        String url = uploadSessionUrl(publisherToken, releaseId, "tool.fyp");
        putBytes(url, PublishingPipelineTest.validPluginZip("dupe.tool", "1.0.0"));
        submit(publisherToken, releaseId);
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");
        decide(publisherToken, reviewerToken, releaseId, "APPROVE");
        awaitStatus(publisherToken, releaseId, "PUBLISHED");
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/yank",
                Http.bearer(publisherToken), null, Map.class).getStatusCode().value());

        ResponseEntity<Map> recreate = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases",
                jsonAuth(publisherToken), Map.of("version", "1.0.0", "channel", "stable"),
                Map.class);
        assertEquals(409, recreate.getStatusCode().value());
        assertEquals("duplicate_version", recreate.getBody().get("code"));

        // A version differing only in build metadata ties on precedence and must
        // be caught by the application gate before the DB ever sees it.
        ResponseEntity<Map> buildOnly = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases",
                jsonAuth(publisherToken),
                Map.of("version", "1.0.0+build.7", "channel", "stable"), Map.class);
        assertEquals(409, buildOnly.getStatusCode().value());
        assertEquals("duplicate_version", buildOnly.getBody().get("code"));
    }

    // ---- helpers ----

    private HttpHeaders octetStream() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return headers;
    }

    private String newListing(String token, String prefix) {
        String slug = prefix + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(token), Map.of("slug", slug, "name", "Rework Org"), Map.class)
                .getStatusCode().value());
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings",
                jsonAuth(token), Map.of("namespace", slug, "slug", "tool", "type", "PLUGIN",
                        "name", "Rework Tool", "summary", "s"), Map.class)
                .getStatusCode().value());
        return listings.findByCoordinate(
                        dev.infinia.store.contract.coordinate.InfiniaCoordinate.parse(
                                "infinia://plugin/" + slug + "/tool"))
                .orElseThrow().id.toString();
    }

    private String createDraft(String token, String listingId, String version) {
        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(token),
                Map.of("version", version, "channel", "stable"), Map.class);
        assertEquals(201, release.getStatusCode().value());
        return (String) release.getBody().get("releaseId");
    }

    private String uploadSessionUrl(String token, String releaseId, String filename) {
        ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/uploads", jsonAuth(token),
                Map.of("filename", filename), Map.class);
        assertEquals(201, upload.getStatusCode().value());
        return (String) upload.getBody().get("uploadUrl");
    }

    private void putBytes(String uploadUrl, byte[] bytes) {
        assertEquals(204, http().exchange(HttpMethod.PUT, uploadUrl, octetStream(), bytes)
                .getStatusCode().value());
    }

    private void submit(String token, String releaseId) {
        assertEquals(202, http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit", Http.bearer(token),
                null, Map.class).getStatusCode().value());
    }

    private void decide(String publisherToken, String reviewerToken, String releaseId,
            String decision) {
        ResponseEntity<List> queue = http().getJson("/api/v1/reviews?status=IN_REVIEW",
                List.class, Http.bearer(reviewerToken));
        String reviewId = null;
        for (Object r : queue.getBody()) {
            Map<?, ?> review = (Map<?, ?>) r;
            if (releaseId.equals(review.get("releaseId"))) {
                reviewId = (String) review.get("reviewId");
            }
        }
        assertNotNull(reviewId, "release " + releaseId + " reached the review queue");
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/reviews/" + reviewId + "/decisions", jsonAuth(reviewerToken),
                Map.of("decision", decision), Map.class).getStatusCode().value());
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
}
