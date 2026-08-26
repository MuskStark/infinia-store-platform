package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Update feed rollout bucketing and delivery tickets (design §8.4, §10.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UpdatesAndDeliveryTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void feedReturnsSignedStableUpdate() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64"
                        + "&installId=fixed-install-id", Map.class, null);
        assertEquals(200, feed.getStatusCode().value());
        Map<String, Object> body = feed.getBody();
        assertEquals("4.1.0", body.get("latestVersion"));
        assertEquals(false, body.get("mandatory"), "forced updates must stay non-mandatory");
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) body.get("artifacts");
        assertTrue(artifacts.stream()
                .anyMatch(a -> "macos".equals(a.get("platform")) && "arm64".equals(a.get("arch"))));
        assertNotNull(body.get("keyId"), "feed must carry the platform signing key id");
        assertNotNull(body.get("sha256"));
    }

    @Test
    void feedReturnsNullWhenUpToDate() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.1.0&channel=stable&os=macos&arch=arm64"
                        + "&installId=whatever", Map.class, null);
        assertEquals(200, feed.getStatusCode().value());
        assertNull(feed.getBody().get("latestVersion"));
    }

    @Test
    void betaRolloutPartitionsInstallIdsStably() {
        boolean sawUpdate = false;
        boolean sawHold = false;
        for (int i = 0; i < 200 && !(sawUpdate && sawHold); i++) {
            String installId = "bucket-probe-" + i;
            ResponseEntity<Map> feed = http().getJson(
                    "/api/v1/updates/app?current=4.1.0&channel=beta&os=macos&arch=arm64"
                            + "&installId=" + installId, Map.class, null);
            if ("4.2.0-beta.1".equals(feed.getBody().get("latestVersion"))) {
                sawUpdate = true;
            } else {
                sawHold = true;
            }
            ResponseEntity<Map> again = http().getJson(
                    "/api/v1/updates/app?current=4.1.0&channel=beta&os=macos&arch=arm64"
                            + "&installId=" + installId, Map.class, null);
            assertEquals(feed.getBody().get("latestVersion"),
                    again.getBody().get("latestVersion"), "cohort must be stable per installId");
        }
        assertTrue(sawUpdate, "some install ids must be inside the 25% beta rollout");
        assertTrue(sawHold, "some install ids must be outside the 25% beta rollout");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadTicketServesBlobBytes() {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/markdown",
                Map.class, null);
        Map<String, Object> release = ((List<Map<String, Object>>) detail.getBody()
                .get("releases")).get(0);
        ResponseEntity<Map> ticket = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + release.get("releaseId")
                        + "/download-ticket?os=universal&arch=universal",
                null, null, Map.class);
        assertEquals(200, ticket.getStatusCode().value());
        String url = (String) ticket.getBody().get("url");
        assertNotNull(url);
        assertEquals(64, ((String) ticket.getBody().get("sha256")).length());

        ResponseEntity<byte[]> blob = http().exchangeJson(HttpMethod.GET, url, null, null,
                byte[].class); // url is server-relative; Http.url() absolutizes
        assertEquals(200, blob.getStatusCode().value());
        assertTrue(blob.getBody().length > 0, "blob bytes must be served");

        // Tampering with the signature must fail closed.
        String tampered = url.substring(0, url.length() - 4) + "beef";
        ResponseEntity<byte[]> rejected = http().exchangeJson(HttpMethod.GET, tampered, null,
                null, byte[].class);
        assertEquals(403, rejected.getStatusCode().value());
    }

    @Test
    void ticketWithoutSignatureIsRejected() {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/markdown",
                Map.class, null);
        Object rawRelease = ((List<?>) detail.getBody().get("releases")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) rawRelease;
        ResponseEntity<Map> ticket = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + release.get("releaseId") + "/download-ticket",
                null, null, Map.class);
        String url = ((String) ticket.getBody().get("url")).split("\\?")[0];
        ResponseEntity<byte[]> rejected = http().exchangeJson(HttpMethod.GET, url, null, null,
                byte[].class);
        assertEquals(403, rejected.getStatusCode().value());
    }
}
