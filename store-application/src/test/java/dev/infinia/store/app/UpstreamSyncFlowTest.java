package dev.infinia.store.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Autowired
    dev.infinia.store.domain.port.BlobStorage blobs;

    private HttpServer upstream;
    private final AtomicInteger payloadRequests = new AtomicInteger();
    private final AtomicInteger metadataRequests = new AtomicInteger();

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
        String upstreamName = "claude-official-" + UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", jsonAuth(adminToken),
                Map.of("name", upstreamName,
                        "marketplaceUrl", "http://127.0.0.1:" + upstream.getAddress().getPort()
                                + "/marketplace.json",
                        "targetNamespace", "claude",
                        "adapterType", "CLAUDE_MARKETPLACE"),
                Map.class);
        assertEquals(201, created.getStatusCode().value());
        String upstreamId = (String) created.getBody().get("upstreamId");

        ResponseEntity<String> denied = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", jsonAuth(null), Map.of(), String.class);
        assertEquals(401, denied.getStatusCode().value());

        // Registration immediately indexes metadata; no separate admin action is
        // required before the upstream item becomes visible.
        assertEquals(Boolean.TRUE, created.getBody().get("lastSyncOk"));
        assertEquals(0, payloadRequests.get(),
                "catalog indexing must not pull the referenced repository artifact");
        assertEquals(2, metadataRequests.get(),
                "transient metadata failures should recover without admin intervention");

        // A manual sync after registration is idempotent.
        ResponseEntity<Map> first = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(adminToken), null,
                Map.class);
        assertEquals(200, first.getStatusCode().value());
        assertEquals(0, ((Number) first.getBody().get("imported")).intValue(),
                "body: " + first.getBody());
        assertEquals(1, ((Number) first.getBody().get("skipped")).intValue());
        assertEquals(0, ((Number) first.getBody().get("failed")).intValue());
        assertEquals(0, payloadRequests.get(),
                "catalog sync must not pull the referenced repository artifact");

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

        ResponseEntity<Map> detail = http().getJson(
                "/api/v1/listings/claude/example-skill", Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        Map<String, Object> provenance = (Map<String, Object>) detail.getBody().get("upstream");
        assertNotNull(provenance, "upstream provenance missing from listing detail");
        assertEquals(upstreamName, provenance.get("sourceName"));
        assertEquals("2.0.0", provenance.get("upstreamVersion"));
        assertEquals("LIVE_NO_RETENTION", provenance.get("deliveryMode"));
        assertTrue(String.valueOf(provenance.get("sourceUrl")).endsWith("/repo.tar.gz"));
        assertNotNull(provenance.get("metadataSha256"));
        assertEquals(0, payloadRequests.get(),
                "listing detail must expose provenance without fetching the artifact");

        String downloadUrl = String.valueOf(aggregated.get("downloadUrl"));
        assertTrue(downloadUrl.contains("/api/v1/blobs/upstream/"));
        String virtualKey = downloadUrl.substring(downloadUrl.indexOf("/blobs/") + 7,
                downloadUrl.indexOf('?'));
        assertFalse(blobs.exists(virtualKey), "virtual upstream key must not exist on disk");

        ResponseEntity<Map> ecosystem = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) ecosystem.getBody().get("plugins");
        assertFalse(plugins.stream().anyMatch(p ->
                        "claude-example-skill".equals(p.get("name"))),
                "upstream payloads must not be retained as local git exports: " + plugins);
        assertEquals(0, payloadRequests.get(),
                "compatibility catalog rendering must remain metadata-only");

        // A user download is the first operation allowed to pull the repository.
        Set<Path> tempBefore = upstreamTempDirectories();
        var download = http().getBytes(downloadUrl.replaceFirst("^http://[^/]+", ""));
        assertEquals(200, download.getStatusCode().value(),
                () -> new String(download.getBody(), StandardCharsets.UTF_8));
        assertEquals("no-store", download.getHeaders().getFirst("Cache-Control"));
        assertNotNull(download.getHeaders().getFirst("X-Checksum-SHA256"));
        Map<String, dev.infinia.store.scanner.SafeZip.ExtractedFile> packed =
                dev.infinia.store.scanner.SafeZip.extract(
                        new java.io.ByteArrayInputStream(download.getBody()),
                        dev.infinia.store.scanner.SafeZip.Limits.defaults());
        assertTrue(packed.containsKey("manifest.json"), "FengYu manifest added on demand");
        assertTrue(packed.containsKey("SKILL.md"), "upstream skill retained in package");
        assertTrue(packed.containsKey("scripts/helper.py"), "skill resources retained");
        assertEquals(1, payloadRequests.get());
        // The workspace is deleted by PreparedArtifact.close() on the streaming
        // thread right after the last byte — the client can observe completion
        // while deleteTree() is still walking the cloned tree, so allow a brief
        // grace period instead of requiring instant disappearance.
        boolean workspaceGone = false;
        for (int i = 0; i < 100 && !workspaceGone; i++) {
            workspaceGone = tempBefore.equals(upstreamTempDirectories());
            if (!workspaceGone) {
                Thread.sleep(50);
            }
        }
        assertTrue(workspaceGone,
                "request-scoped upstream workspace must be deleted after streaming: "
                        + upstreamTempDirectories());
        assertFalse(blobs.exists(virtualKey),
                "downloaded/generated upstream package must not be retained on disk");

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
                + "\"version\":\"" + version + "\","
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
            if (metadataRequests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, marketplace.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(marketplace);
            }
            exchange.close();
        });
        upstream.createContext("/repo.tar.gz", exchange -> {
            payloadRequests.incrementAndGet();
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

    private static Set<Path> upstreamTempDirectories() throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(temp)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> String.valueOf(path.getFileName())
                            .startsWith("infinia-upstream-"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
