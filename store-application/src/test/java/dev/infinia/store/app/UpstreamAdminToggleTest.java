package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Operators can park a persistently failing upstream source at runtime: the
 * upstream probe skips disabled sources, so an optional aggregation mirror
 * being down must not keep the whole status page yellow while the store
 * itself is healthy.
 *
 * <p>Status reads serve the sampler's cached snapshot (reads no longer run
 * probes), so a config change surfaces within one sampling window rather
 * than instantly. This test pins the window at 250 ms and polls to the next
 * sample instead of expecting a same-request flip.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "store.status.sample-interval-ms=250")
@ActiveProfiles("test")
class UpstreamAdminToggleTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.app.service.StatusService status;

    @Test
    @SuppressWarnings("unchecked")
    void disablingAFailingSourceTurnsTheStatusPageGreenAgain() throws InterruptedException {
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        // A failing source: nothing listens on the discard port.
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", json(admin),
                Map.of("name", "dead-" + UUID.randomUUID().toString().substring(0, 6),
                        "marketplaceUrl", "http://127.0.0.1:9/marketplace.json",
                        "targetNamespace", "dead"),
                Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String upstreamId = (String) created.getBody().get("upstreamId");
        assertEquals(Boolean.FALSE, created.getBody().get("lastSyncOk"));

        assertEquals(dev.infinia.store.app.service.StatusService.DEGRADED,
                awaitUpstreamIndicator(dev.infinia.store.app.service.StatusService.DEGRADED),
                "a failing enabled source degrades the upstream component within one sampling window");

        // Park it: PATCH enabled=false. (The shared Http helper rides
        // HttpURLConnection, which rejects PATCH; JDK HttpClient does not.)
        org.springframework.web.client.RestTemplate patchCapable =
                new org.springframework.web.client.RestTemplate(
                        new org.springframework.http.client.JdkClientHttpRequestFactory());
        ResponseEntity<Map> parked = patchCapable.exchange(
                "http://127.0.0.1:" + port + "/api/v1/admin/upstreams/" + upstreamId,
                HttpMethod.PATCH, new org.springframework.http.HttpEntity<>(
                        Map.of("enabled", false), json(admin)), Map.class);
        assertEquals(HttpStatus.OK, parked.getStatusCode());
        assertFalse((Boolean) parked.getBody().get("enabled"));

        ResponseEntity<List> listed = http().getJson("/api/v1/admin/upstreams",
                List.class, Http.bearer(admin));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) listed.getBody().stream()
                .filter(s -> upstreamId.equals(((Map<?, ?>) s).get("upstreamId")))
                .findFirst().orElseThrow()).get("enabled"));

        assertEquals(dev.infinia.store.app.service.StatusService.OPERATIONAL,
                awaitUpstreamIndicator(dev.infinia.store.app.service.StatusService.OPERATIONAL),
                "a disabled source must not keep the page yellow");
    }

    /** Polls the cached page until the next sample reflects {@code expected}. */
    private String awaitUpstreamIndicator(String expected) throws InterruptedException {
        String indicator = upstreamIndicator();
        for (long deadline = System.currentTimeMillis() + 15_000;
                !expected.equals(indicator) && System.currentTimeMillis() < deadline;
                Thread.sleep(150)) {
            indicator = upstreamIndicator();
        }
        return indicator;
    }

    @SuppressWarnings("unchecked")
    private String upstreamIndicator() {
        ResponseEntity<Map> page = http().getJson("/api/v1/status", Map.class, null);
        return ((List<Map<String, Object>>) page.getBody().get("components")).stream()
                .filter(c -> "upstream".equals(c.get("key")))
                .map(c -> String.valueOf(c.get("indicator")))
                .findFirst().orElseThrow();
    }

    private Http http() {
        return new Http(port);
    }

    private static HttpHeaders json(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
