package dev.infinia.store.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline install packages (aggregation plan §5.2 / §7.1): the web store's
 * direct download for plugins, skills and MCP templates. One ZIP carries the
 * Native install manifest, the signed artifact and sha256sum checksums, so
 * the downloaded file installs through the host's local install mode with
 * full provenance and no store connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InstallPackageDownloadTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pluginPackageCarriesManifestArtifactAndChecksums() throws Exception {
        String releaseId = publishedReleaseId("official", "markdown");
        long downloadsBefore = listingDownloads("official", "markdown");

        ResponseEntity<byte[]> pkg = http().getBytes(
                "/api/v1/releases/" + releaseId + "/install-package");
        assertEquals(200, pkg.getStatusCode().value());
        assertEquals("application/zip", pkg.getHeaders().getContentType().toString());
        assertEquals("attachment; filename=\"official.markdown-2.4.0-install-package.zip\"",
                pkg.getHeaders().getFirst("Content-Disposition"));

        Map<String, byte[]> entries = unzip(pkg.getBody());
        assertEquals(3, entries.size(), "entries: " + entries.keySet());
        byte[] artifactBytes = entries.get("artifact/markdown-2.4.0.fyp");
        byte[] manifestBytes = entries.get("install-manifest.json");
        assertNotNull(artifactBytes, "the packaged artifact must be present");
        assertTrue(artifactBytes.length > 0);
        assertNotNull(manifestBytes);

        // The manifest is the Native install contract (plan §7.1) — the same
        // document the host fetches online, plus the package layout block.
        Map<String, Object> manifest = json(manifestBytes);
        assertEquals(1, ((Number) manifest.get("schemaVersion")).intValue());
        assertEquals("infinia://plugin/official/markdown@2.4.0", manifest.get("coordinate"));
        assertEquals("PLUGIN", manifest.get("type"));
        assertEquals(releaseId, manifest.get("sourceReleaseId"));
        Map<String, Object> artifact = (Map<String, Object>) manifest.get("artifact");
        assertEquals("IMMUTABLE_BLOB", artifact.get("delivery"));
        assertEquals("markdown-2.4.0.fyp", artifact.get("filename"));
        assertEquals(sha256Hex(artifactBytes), artifact.get("sha256"),
                "the embedded digest must match the packaged bytes — the host verifies this");
        assertFalse(String.valueOf(artifact.get("signature")).isEmpty(),
                "the platform Ed25519 signature must travel with the package");
        assertTrue(String.valueOf(artifact.get("keyId"))
                .startsWith(dev.infinia.store.app.service.PlatformSigningService
                        .PLATFORM_KEY_ID_PREFIX));
        Map<String, Object> install = (Map<String, Object>) manifest.get("install");
        assertEquals("PLUGIN_PACKAGE", install.get("mode"));
        assertEquals(true, install.get("defaultEnabled"));
        Map<String, Object> packageBlock = (Map<String, Object>) manifest.get("package");
        assertEquals("infinia-install-package", packageBlock.get("format"));
        assertEquals("artifact/markdown-2.4.0.fyp",
                ((Map<String, Object>) packageBlock.get("files")).get("artifact"));

        // checksums.txt stays sha256sum -c compatible for both files.
        String checksums = new String(entries.get("checksums.txt"), StandardCharsets.UTF_8);
        assertEquals(sha256Hex(artifactBytes) + "  artifact/markdown-2.4.0.fyp\n"
                + sha256Hex(manifestBytes) + "  install-manifest.json\n", checksums);

        // The packaged bytes are exactly the ticketed blob bytes — one artifact,
        // one digest, whichever surface the user downloads from.
        ResponseEntity<Map> ticket = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/download-ticket", null, null, Map.class);
        ResponseEntity<byte[]> blob = http().exchangeJson(HttpMethod.GET,
                (String) ticket.getBody().get("url"), null, null, byte[].class);
        assertArrayEquals(blob.getBody(), artifactBytes);

        // A completed package download counts as a listing download (audit 3.1).
        assertTrue(awaitDownloads("official", "markdown", downloadsBefore + 1),
                "package downloads must be counted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillPackageInstallsAsSkillDirectory() throws Exception {
        String releaseId = publishedReleaseId("official", "pdf-tools");
        ResponseEntity<byte[]> pkg = http().getBytes(
                "/api/v1/releases/" + releaseId + "/install-package");
        assertEquals(200, pkg.getStatusCode().value());
        Map<String, byte[]> entries = unzip(pkg.getBody());
        byte[] fys = entries.get("artifact/pdf-tools-1.3.0.fys");
        assertNotNull(fys, "entries: " + entries.keySet());
        Map<String, Object> manifest = json(entries.get("install-manifest.json"));
        Map<String, Object> install = (Map<String, Object>) manifest.get("install");
        assertEquals("SKILL_DIRECTORY", install.get("mode"));
        assertEquals(true, install.get("defaultEnabled"));
        assertEquals(sha256Hex(fys),
                ((Map<String, Object>) manifest.get("artifact")).get("sha256"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpPackageStaysDisabledWithLocalSecrets() throws Exception {
        String releaseId = publishedReleaseId("official", "calendar");
        ResponseEntity<byte[]> pkg = http().getBytes(
                "/api/v1/releases/" + releaseId + "/install-package");
        assertEquals(200, pkg.getStatusCode().value());
        Map<String, byte[]> entries = unzip(pkg.getBody());
        assertNotNull(entries.get("artifact/calendar-1.0.0.mcp.json"),
                "entries: " + entries.keySet());
        Map<String, Object> manifest = json(entries.get("install-manifest.json"));
        Map<String, Object> install = (Map<String, Object>) manifest.get("install");
        assertEquals("MCP_TEMPLATE", install.get("mode"));
        assertEquals(false, install.get("defaultEnabled"), "plan §6.2: MCP never enabled on install");
        assertEquals("LOCAL_ONLY", install.get("secretsPolicy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void draftReleaseIsRejectedWithProblem() {
        String adminToken = dev.infinia.store.app.AuthTestSupport.login(http(), null,
                "admin@infinia.local", dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String slug = "pkg-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(adminToken), Map.of("slug", slug, "name", "Pkg Org"), Map.class)
                .getStatusCode().value());
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings",
                jsonAuth(adminToken), Map.of("namespace", slug, "slug", "tool", "type", "PLUGIN",
                        "category", "Productivity", "name", "Pkg Tool", "summary", "draft only"),
                Map.class).getStatusCode().value());
        String listingId = listingUuid(slug, "tool");
        ResponseEntity<Map> draft = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(adminToken),
                Map.of("version", "0.2.0", "channel", "stable"), Map.class);
        assertEquals(201, draft.getStatusCode().value());
        String draftReleaseId = (String) draft.getBody().get("releaseId");

        ResponseEntity<Map> rejected = http().exchangeJson(HttpMethod.GET,
                "/api/v1/releases/" + draftReleaseId + "/install-package", null, null, Map.class);
        assertEquals(409, rejected.getStatusCode().value());
        assertEquals("invalid_state_transition", rejected.getBody().get("code"),
                "body: " + rejected.getBody());

        // Unknown releases answer as problems too, never as a broken zip stream.
        ResponseEntity<Map> missing = http().exchangeJson(HttpMethod.GET,
                "/api/v1/releases/" + UUID.randomUUID() + "/install-package", null, null,
                Map.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void beeLevelGateBlocksAnonymousPackageDownload() {
        String adminToken = dev.infinia.store.app.AuthTestSupport.login(http(), null,
                "admin@infinia.local", dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String releaseId = publishedReleaseId("official", "markdown");
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/listings/" + listingUuid("official", "markdown") + "/min-bee-level",
                jsonAuth(adminToken), Map.of("minBeeLevel", 1), Map.class)
                .getStatusCode().value());

        ResponseEntity<byte[]> blocked = http().getBytes(
                "/api/v1/releases/" + releaseId + "/install-package");
        assertEquals(403, blocked.getStatusCode().value());

        // A viewer at the required level downloads normally; the gate, not the
        // packaging, is what refused the anonymous request.
        String workerToken = dev.infinia.store.app.AuthTestSupport.login(http(), null,
                "user@infinia.local", dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        ResponseEntity<byte[]> allowed = http().exchangeJson(HttpMethod.GET,
                "/api/v1/releases/" + releaseId + "/install-package",
                Http.bearer(workerToken), null, byte[].class);
        assertEquals(200, allowed.getStatusCode().value());
    }

    // ---- helpers ----

    private String publishedReleaseId(String namespace, String slug) {
        ResponseEntity<Map> detail = http().getJson(
                "/api/v1/listings/" + namespace + "/" + slug, Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        return ((List<Map<String, Object>>) (List<?>) detail.getBody().get("releases")).stream()
                .filter(r -> "PUBLISHED".equals(r.get("status")))
                .map(r -> (String) r.get("releaseId"))
                .findFirst().orElseThrow();
    }

    private long listingDownloads(String namespace, String slug) {
        ResponseEntity<Map> detail = http().getJson(
                "/api/v1/listings/" + namespace + "/" + slug, Map.class, null);
        return ((Number) detail.getBody().get("downloads")).longValue();
    }

    private boolean awaitDownloads(String namespace, String slug, long expected)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (listingDownloads(namespace, slug) >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @org.springframework.beans.factory.annotation.Autowired
    dev.infinia.store.domain.port.ListingRepository listings;

    /** Methods share the class database — drop the gate the bee-level test raised. */
    @AfterEach
    void resetGate() {
        listings.findByCoordinate(dev.infinia.store.contract.coordinate.InfiniaCoordinate
                .parse("infinia://plugin/official/markdown")).ifPresent(listing -> {
            listing.minBeeLevel = 0;
            listings.save(listing);
        });
    }

    private String listingUuid(String namespace, String slug) {
        return listings.findByCoordinate(dev.infinia.store.contract.coordinate.InfiniaCoordinate
                .parse("infinia://plugin/" + namespace + "/" + slug)).orElseThrow().id.toString();
    }

    private static org.springframework.http.HttpHeaders jsonAuth(String token) {
        org.springframework.http.HttpHeaders headers = Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null;
                    entry = in.getNextEntry()) {
                assertFalse(entry.isDirectory(), "unexpected directory entry " + entry.getName());
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(byte[] bytes) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(bytes, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Bad manifest JSON in package", e);
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
