package dev.infinia.store.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggregation-plan conformance for the new upstream stack (plan §3/§4/§6/§8):
 * MCP Registry ingestion with provenance, SSRF blocking, native install
 * manifests and the MCP/Codex compatibility surfaces. Local HTTP fixtures
 * stand in for the registry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UpstreamAdaptersConformanceTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.UpstreamRepositories.UpstreamItemRepository upstreamItems;

    private HttpServer registry;

    @AfterEach
    void stop() {
        if (registry != null) {
            registry.stop(0);
        }
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpRegistrySyncPublishesTemplateWithProvenanceAndIdempotency() throws Exception {
        registry = startRegistry("""
                {"name":"everything","description":"Remote + npm stdio test server",
                 "version":"1.4.0",
                 "remotes":[{"transport_type":"streamable-http",
                             "url":"https://mcp.example.com/mcp",
                             "headers":{"authorization":""}}],
                 "packages":[{"registry_type":"npm","identifier":"@example/mcp-everything",
                              "version":"1.4.0",
                              "checksum":"sha256-a1b2c3d4e5f60718293a4b5c6d7e8f90"}]}
                """);
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String ns = "mcp-" + UUID.randomUUID().toString().substring(0, 6);
        Map created = (Map) http().exchangeJson(HttpMethod.POST, "/api/v1/admin/upstreams",
                jsonAuth(admin),
                Map.of("name", "registry-" + ns, "marketplaceUrl",
                        "http://127.0.0.1:" + registry.getAddress().getPort() + "/server.json",
                        "targetNamespace", ns, "adapterType", "MCP_REGISTRY"),
                Map.class).getBody();
        String upstreamId = (String) created.get("upstreamId");
        assertEquals(Boolean.TRUE, created.get("lastSyncOk"), "body: " + created);

        // The MCP entry is live with a direct template download + provenance recorded.
        List<Map<String, Object>> mcp = (List<Map<String, Object>>) http()
                .getJson("/api/v1/compat/fengyu/mcp-catalog", List.class, null).getBody();
        Map<String, Object> entry = mcp.stream()
                .filter(e -> String.valueOf(e.get("id")).startsWith(ns + ".")).findFirst()
                .orElseThrow(() -> new AssertionError("mcp missing: " + mcp));
        assertEquals("1.4.0", entry.get("version"));
        assertTrue(String.valueOf(entry.get("downloadUrl")).contains("/api/v1/blobs/"));
        assertFalse((Boolean) entry.get("official"));
        assertEquals(1, upstreamItems.findBySource(java.util.UUID.fromString(upstreamId)).size(),
                "provenance row recorded");

        // Re-sync is idempotent via the exact content digest.
        Map second = (Map) http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(admin), null,
                Map.class).getBody();
        assertEquals(0, ((Number) second.get("imported")).intValue());
        assertEquals(1, ((Number) second.get("skipped")).intValue(), "body: " + second);

        // Pass-through: the artifact is virtual — no blob is persisted locally.
        String dl = String.valueOf(entry.get("downloadUrl"));
        assertTrue(dl.contains("/api/v1/blobs/upstream/"),
                "virtual key in URL: " + dl);
        var dlResp = http().get(dl.replaceFirst("^http://[^/]+", ""), null);
        assertEquals(200, dlResp.getStatusCode().value());
        assertTrue(dlResp.getBody() != null && dlResp.getBody().contains("STREAMABLE_HTTP"),
                "template streamed from rebuild: " + dlResp.getBody());

        // Upstream drift: change the served server.json, download must fail 409
        // instead of serving bytes that no longer match the recorded digest.
        // Same port, changed content — a real drift, not a dead endpoint.
        int registryPort = registry.getAddress().getPort();
        registry.stop(0);
        registry = startRegistry(registryPort, """
                {"name":"everything","description":"DRIFTED description",
                 "version":"1.4.0",
                 "remotes":[{"transport_type":"streamable-http",
                             "url":"https://mcp.example.com/mcp",
                             "headers":{"authorization":""}}]}
                """);
        var drifted = http().get(dl.replaceFirst("^http://[^/]+", ""), null);
        assertEquals(409, drifted.getStatusCode().value(), "body: " + drifted.getBody());
        assertTrue(String.valueOf(drifted.getBody()).contains("upstream_drifted"),
                drifted.getBody());
    }

    @Test
    @SuppressWarnings("unchecked")
    void installManifestCarriesSignatureAndInstallMode() throws Exception {
        // Any published release works; use a seeded one.
        Map listing = (Map) http().getJson("/api/v1/listings/official/markdown", Map.class,
                null).getBody();
        String releaseId = ((List<Map<String, Object>>) listing.get("releases")).get(0)
                .get("releaseId").toString();
        Map manifest = (Map) http().getJson(
                "/api/v1/releases/" + releaseId + "/install-manifest?client=fengyu",
                Map.class, null).getBody();
        assertEquals(1, manifest.get("schemaVersion"));
        assertEquals("PLUGIN", manifest.get("type"));
        Map artifact = (Map<String, Object>) manifest.get("artifact");
        assertTrue(String.valueOf(artifact.get("url")).startsWith("http"));
        assertEquals(64, String.valueOf(artifact.get("sha256")).length());
        assertNotNull(artifact.get("keyId"));
        Map install = (Map<String, Object>) manifest.get("install");
        assertEquals("PLUGIN_PACKAGE", install.get("mode"));
        assertEquals(Boolean.TRUE, install.get("defaultEnabled"));

        // MCP manifests must default to disabled (plan §6.2).
        List<Map<String, Object>> mcp = (List<Map<String, Object>>) http()
                .getJson("/api/v1/compat/fengyu/mcp-catalog", List.class, null).getBody();
        if (!mcp.isEmpty()) {
            // resolve any MCP listing's latest release via listing detail
            Map detail = (Map) http().getJson("/api/v1/catalog?type=MCP", Map.class, null)
                    .getBody();
            List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
            if (!items.isEmpty()) {
                String coordinate = items.get(0).get("coordinate").toString();
                String[] parts = coordinate.replace("infinia://mcp/", "").split("/");
                Map mcpDetail = (Map) http().getJson(
                        "/api/v1/listings/" + parts[0] + "/" + parts[1], Map.class, null)
                        .getBody();
                String mcpReleaseId = ((List<Map<String, Object>>) mcpDetail.get("releases"))
                        .get(0).get("releaseId").toString();
                Map mcpManifest = (Map) http().getJson(
                        "/api/v1/releases/" + mcpReleaseId + "/install-manifest", Map.class,
                        null).getBody();
                Map mcpInstall = (Map<String, Object>) mcpManifest.get("install");
                assertEquals(Boolean.FALSE, mcpInstall.get("defaultEnabled"));
                assertEquals("LOCAL_ONLY", mcpInstall.get("secretsPolicy"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ssrfAndInternalTargetsAreBlocked() throws Exception {
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        Map created = (Map) http().exchangeJson(HttpMethod.POST, "/api/v1/admin/upstreams",
                jsonAuth(admin),
                Map.of("name", "ssrf-" + UUID.randomUUID().toString().substring(0, 6),
                        "marketplaceUrl", "http://127.0.0.1:169.254.169.254/latest/meta-data",
                        "targetNamespace", "blocked"),
                Map.class).getBody();
        assertEquals(Boolean.FALSE, created.get("lastSyncOk"));
        String error = String.valueOf(created.get("lastError")).toLowerCase();
        assertTrue(error.contains("blocked") || error.contains("resolve")
                        || error.contains("aborted") || error.contains("no host"),
                "SSRF attempt must fail loudly: " + created);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillhubRegistryAggregatesWorkBuddySkillsWithRedirectDownloadAndDriftGuard()
            throws Exception {
        registry = startSkillHub("""
                {"code":0,"message":"success","data":{"total":2,"skills":[
                  {"slug":"wb-doc-writer","source":"official","name":"Doc Writer",
                   "description":"Writes polished documents","description_zh":"写出精致文档",
                   "version":"1.2.0","homepage":"http://127.0.0.1:0/wb-doc-writer",
                   "tags":["latest"],"downloads":9001},
                  {"slug":"wb-sheet-magic","source":"community","name":"Sheet Magic",
                   "description":"Spreadsheet automation","description_zh":"表格自动化",
                   "version":"0.9.0","homepage":"http://127.0.0.1:0/wb-sheet-magic",
                   "tags":["latest"],"downloads":4321}]}}
                """);
        int skillhubPort = registry.getAddress().getPort();
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String ns = "wb-" + UUID.randomUUID().toString().substring(0, 6);

        // Registered with the bare API base URL — the adapter derives /api/skills.
        Map created = (Map) http().exchangeJson(HttpMethod.POST, "/api/v1/admin/upstreams",
                jsonAuth(admin),
                Map.of("name", "skillhub-" + ns, "marketplaceUrl",
                        "http://127.0.0.1:" + skillhubPort, "targetNamespace", ns,
                        "adapterType", "SKILLHUB_REGISTRY"),
                Map.class).getBody();
        assertEquals(Boolean.TRUE, created.get("lastSyncOk"), "body: " + created);
        assertEquals(0, skillhubPayloadRequests.get(),
                "catalog indexing must not download any skill zip");

        // Both WorkBuddy skills surface on the host catalog with upstream versions;
        // the Chinese description wins per the adapter's locale preference.
        List<Map<String, Object>> skills = (List<Map<String, Object>>) http()
                .getJson("/api/v1/compat/fengyu/skills-catalog", List.class, null).getBody();
        Map<String, Object> docWriter = skills.stream()
                .filter(s -> (ns + ".wb-doc-writer").equals(s.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("skill missing: " + skills));
        assertEquals("1.2.0", docWriter.get("version"));
        assertEquals("写出精致文档", docWriter.get("description"));
        assertTrue(String.valueOf(docWriter.get("downloadUrl")).contains(
                "/api/v1/blobs/upstream/"), "virtual pass-through key in URL");
        assertEquals(2, upstreamItems.findBySource(java.util.UUID.fromString(
                        (String) created.get("upstreamId"))).size(),
                "provenance rows recorded for both skills");

        // Re-sync is idempotent via the exact metadata digest.
        Map second = (Map) http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + created.get("upstreamId") + "/sync",
                jsonAuth(admin), null, Map.class).getBody();
        assertEquals(0, ((Number) second.get("imported")).intValue(), "body: " + second);
        assertEquals(2, ((Number) second.get("skipped")).intValue());

        // The payload download follows the 302 to object storage, scans, and
        // compatibility-packs on demand without persisting anything.
        String dl = String.valueOf(docWriter.get("downloadUrl"));
        var dlResp = http().getBytes(dl.replaceFirst("^http://[^/]+", ""));
        assertEquals(200, dlResp.getStatusCode().value(),
                "body: " + new String(dlResp.getBody(), StandardCharsets.UTF_8));
        assertEquals(1, skillhubPayloadRequests.get(),
                "download hits the redirecting endpoint exactly once");
        Map<String, dev.infinia.store.scanner.SafeZip.ExtractedFile> packed =
                dev.infinia.store.scanner.SafeZip.extract(
                        new java.io.ByteArrayInputStream(dlResp.getBody()),
                        dev.infinia.store.scanner.SafeZip.Limits.defaults());
        assertTrue(packed.containsKey("manifest.json"), "FengYu manifest added");
        assertTrue(packed.containsKey("SKILL.md"), "SKILL.md retained");
        assertTrue(packed.containsKey("scripts/helper.py"), "skill resources retained");

        // Upstream drift (version bump in the catalog): download must fail 409
        // instead of serving a payload that no longer matches the synced digest.
        registry.stop(0);
        registry = startSkillHub(skillhubPort, """
                {"code":0,"message":"success","data":{"total":1,"skills":[
                  {"slug":"wb-doc-writer","source":"official","name":"Doc Writer",
                   "description":"Writes polished documents","description_zh":"写出精致文档",
                   "version":"1.3.0","homepage":"http://127.0.0.1:0/wb-doc-writer",
                   "tags":["latest"],"downloads":9001}]}}
                """);
        var drifted = http().get(dl.replaceFirst("^http://[^/]+", ""), null);
        assertEquals(409, drifted.getStatusCode().value(), "body: " + drifted.getBody());
        assertTrue(String.valueOf(drifted.getBody()).contains("upstream_drifted"),
                drifted.getBody());
    }

    private final java.util.concurrent.atomic.AtomicInteger skillhubPayloadRequests =
            new java.util.concurrent.atomic.AtomicInteger();

    /** SkillHub stand-in: catalog envelope + 302 download endpoint + zipped skills. */
    private HttpServer startSkillHub(String catalog) throws IOException {
        return startSkillHub(0, catalog);
    }

    private HttpServer startSkillHub(int port, String catalog) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        byte[] catalogBody = catalog.getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/skills", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean firstPage = query == null || !query.contains("page=2");
            byte[] body = firstPage ? catalogBody
                    : "{\"code\":0,\"data\":{\"total\":0,\"skills\":[]}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/api/v1/download", exchange -> {
            skillhubPayloadRequests.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            String slug = java.util.Arrays.stream(query.split("&"))
                    .filter(p -> p.startsWith("slug=")).findFirst().orElse("=");
            slug = java.net.URLDecoder.decode(slug.substring(5), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/storage/" + slug + ".zip");
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        server.createContext("/storage/", exchange -> {
            String slug = exchange.getRequestURI().getPath()
                    .substring("/storage/".length()).replace(".zip", "");
            byte[] zip = skillZip(slug);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, zip.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(zip);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] skillZip(String slug) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry skillMd = new java.util.zip.ZipEntry(slug + "/SKILL.md");
            zip.putNextEntry(skillMd);
            zip.write(("---\nname: " + slug + "\ndescription: SkillHub skill " + slug
                    + "\n---\n# " + slug + "\nUpstream body.").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            java.util.zip.ZipEntry helper = new java.util.zip.ZipEntry(
                    slug + "/scripts/helper.py");
            zip.putNextEntry(helper);
            zip.write("print('helper')".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private HttpServer startRegistry(String serverJson) throws IOException {
        return startRegistry(0, serverJson);
    }

    private HttpServer startRegistry(int port, String serverJson) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        byte[] body = serverJson.getBytes(StandardCharsets.UTF_8);
        server.createContext("/server.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (InputStream ignored = exchange.getRequestBody()) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }
}
