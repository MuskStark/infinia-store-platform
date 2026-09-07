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
 * Update-feed surfaces and delivery tickets (design §8.4, §10.2).
 *
 * <p>The JSON feed {@code GET /api/v1/updates/app} is deliberately RESERVED
 * (audit 3.5) — the desktop client consumes the electron-updater deb feed at
 * {@code /fengyu-updates/deb} instead, which FengYuUpdateFeedTest covers.
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
    void reservedAppFeedAnswers501WithHonestProblemDetail() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=linux&arch=x64"
                        + "&installId=fixed-install-id", Map.class, null);
        assertEquals(501, feed.getStatusCode().value());
        Map<String, Object> body = feed.getBody();
        assertEquals("reserved", body.get("code"), "body: " + body);
        String detail = String.valueOf(body.get("detail"));
        assertTrue(detail.contains("/fengyu-updates/deb"),
                "the problem must point at the live feed: " + detail);
    }

    @Test
    @SuppressWarnings("unchecked")
    void downloadTicketServesBlobBytes() throws Exception {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/markdown",
                Map.class, null);
        Map<String, Object> release = ((List<Map<String, Object>>) detail.getBody()
                .get("releases")).get(0);
        long downloadsBefore = ((Number) detail.getBody().get("downloads")).longValue();
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
        assertEquals(blob.getBody().length, Integer.parseInt(
                        blob.getHeaders().getFirst("Content-Length")),
                "blob downloads must carry Content-Length (Files.size / S3 HEAD)");

        // A completed artifact download increments the listing counter (the
        // increment runs on the streaming thread; give it a brief grace period).
        assertTrue(awaitDownloads(downloadsBefore + 1),
                "successful downloads must be counted");

        // Tampering with the signature must fail closed.
        String tampered = url.substring(0, url.length() - 4) + "beef";
        ResponseEntity<byte[]> rejected = http().exchangeJson(HttpMethod.GET, tampered, null,
                null, byte[].class);
        assertEquals(403, rejected.getStatusCode().value());
    }

    private boolean awaitDownloads(long expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/markdown",
                    Map.class, null);
            if (((Number) detail.getBody().get("downloads")).longValue() >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
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
