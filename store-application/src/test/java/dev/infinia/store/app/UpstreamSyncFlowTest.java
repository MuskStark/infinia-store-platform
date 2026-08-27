package dev.infinia.store.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upstream aggregation (design §2.1): the store mirrors an external Claude
 * marketplace into its own catalog through the full publish pipeline, so hosts
 * configure only the store. A local HTTP server stands in for the upstream
 * (marketplace.json + skill repository tarball).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UpstreamSyncFlowTest {

    @LocalServerPort
    int port;

    private HttpServer upstream;

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesUpstreamSkillsThroughTheFullPipeline() throws Exception {
        startUpstream("2.0.0", "Create polished PDF reports from raw notes.");
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        // Register the upstream (PLATFORM_ADMIN only).
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", jsonAuth(adminToken),
                Map.of("name", "claude-official-" + UUID.randomUUID().toString().substring(0, 6),
                        "marketplaceUrl", "http://127.0.0.1:" + upstream.getAddress().getPort()
                                + "/marketplace.json",
                        "targetNamespace", "claude"),
                Map.class);
        assertEquals(201, created.getStatusCode().value());
        String upstreamId = (String) created.getBody().get("upstreamId");

        ResponseEntity<String> denied = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", jsonAuth(null), Map.of(), String.class);
        assertEquals(401, denied.getStatusCode().value());

        // First sync imports the skill and publishes it.
        ResponseEntity<Map> first = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(adminToken), null,
                Map.class);
        assertEquals(200, first.getStatusCode().value());
        assertEquals(1, ((Number) first.getBody().get("imported")).intValue(),
                "body: " + first.getBody());
        assertEquals(0, ((Number) first.getBody().get("failed")).intValue());

        // The aggregated skill reaches both host-facing skill surfaces, versioned
        // from the upstream SKILL.md frontmatter.
        ResponseEntity<List> skills = http().getJson("/api/v1/compat/fengyu/skills-catalog",
                List.class, null);
        Map<String, Object> aggregated = (Map<String, Object>) skills.getBody().stream()
                .filter(s -> "claude.example-skill".equals(((Map<?, ?>) s).get("id")))
                .findFirst().orElse(null);
        assertNotNull(aggregated, "skill missing from catalog");
        assertEquals("2.0.0", aggregated.get("version"));
        assertEquals("claude", aggregated.get("author"));

        ResponseEntity<Map> ecosystem = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) ecosystem.getBody().get("plugins");
        assertTrue(plugins.stream().anyMatch(p ->
                        "claude-example-skill".equals(p.get("name"))
                                && ((Map<String, Object>) p.get("source")).get("url")
                                        .toString().startsWith("file://")),
                "exported for the ecosystem source: " + plugins);

        // Second sync with unchanged content is idempotent.
        ResponseEntity<Map> second = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(adminToken), null,
                Map.class);
        assertEquals(0, ((Number) second.getBody().get("imported")).intValue());
        assertEquals(1, ((Number) second.getBody().get("skipped")).intValue());
    }

    /** Minimal stand-in for the official Claude skills marketplace. */
    private void startUpstream(String version, String description) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] marketplace = ("{\"plugins\":[{\"name\":\"Example Skill\","
                + "\"description\":\"" + description + "\","
                + "\"source\":{\"source\":\"url\",\"url\":\"http://127.0.0.1:"
                + "PORTHOLDER/repo.tar.gz\"}}]}")
                        .replace("PORTHOLDER", String.valueOf(upstream.getAddress().getPort()))
                .getBytes(StandardCharsets.UTF_8);
        byte[] skillMd = ("---\nname: example-skill\ndescription: " + description
                + "\nversion: " + version + "\n---\n# Example Skill\nUpstream body.")
                .getBytes(StandardCharsets.UTF_8);
        byte[] helper = "print('helper')".getBytes(StandardCharsets.UTF_8);

        Map<String, byte[]> repo = new java.util.LinkedHashMap<>();
        repo.put("repo-HEAD/example-skill/SKILL.md", skillMd);
        repo.put("repo-HEAD/example-skill/scripts/helper.py", helper);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream compressor = new GZIPOutputStream(gz)) {
            compressor.write(dev.infinia.store.scanner.TarGz.tar(repo));
        }

        upstream.createContext("/marketplace.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, marketplace.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(marketplace);
            }
            exchange.close();
        });
        upstream.createContext("/repo.tar.gz", exchange -> {
            byte[] body = gz.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        upstream.start();
    }

    Http http() {
        return new Http(port);
    }
}
