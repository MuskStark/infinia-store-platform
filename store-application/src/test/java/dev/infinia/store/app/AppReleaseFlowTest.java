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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main-application (ListingType.APP) releases: the installed + portable
 * distribution matrix per platform, per-artifact download tickets and the
 * admin app-release flow. (The historic JSON update feed at
 * /api/v1/updates/app is RESERVED — audit 3.5; the desktop deb feed lives in
 * FengYuUpdateFeedTest.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AppReleaseFlowTest {

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
    void publishingScriptCreatesListingBeforeDraftAndUploadsRc2() throws Exception {
        java.nio.file.Path assets = java.nio.file.Files.createTempDirectory("rc2-publish-");
        try {
            java.nio.file.Files.writeString(assets.resolve("Infinia-4.0.0-rc.2-linux-x64.deb"),
                    "script integration artifact");
            java.nio.file.Path script = java.nio.file.Path.of("../scripts/publish-app-release.sh")
                    .toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(script)) {
                script = java.nio.file.Path.of("scripts/publish-app-release.sh").toAbsolutePath();
            }
            ProcessBuilder builder = new ProcessBuilder("bash", script.toString(),
                    "4.0.0-rc.2", assets.toString(), "rc").redirectErrorStream(true);
            builder.environment().put("STORE_BASE", "http://localhost:" + port);
            builder.environment().put("STORE_APP_NAMESPACE", "script-" + UUID.randomUUID().toString().substring(0, 8));
            builder.environment().put("STORE_SUBMIT", "0");
            java.nio.file.Path log = assets.resolve("publish.log");
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            String output = java.nio.file.Files.readString(log);
            assertTrue(finished, output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("1 assets uploaded"), output);
            assertTrue(output.contains("left in DRAFT"), output);
        } finally {
            try (var files = java.nio.file.Files.list(assets)) {
                for (var file : files.toList()) java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(assets);
        }
    }

    /** The seeded FengYu host release must expose installer AND portable
     *  distributions for every desktop platform plus the universal variants. */
    @Test
    @SuppressWarnings("unchecked")
    void seededHostMatrixCoversInstallersAndPortablesPerPlatform() {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/fengyu-host",
                Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        Map<String, Object> release = ((List<Map<String, Object>>) detail.getBody()
                .get("releases")).stream()
                .filter(r -> "4.1.0".equals(r.get("version")))
                .findFirst().orElseThrow(() -> new AssertionError("seeded 4.1.0 missing"));
        assertEquals("PUBLISHED", release.get("status"));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) release
                .get("artifacts");
        assertTrue(artifacts.stream().anyMatch(a -> "INSTALLER".equals(a.get("kind"))
                && "windows".equals(a.get("platform")) && "lite".equals(a.get("variant"))),
                "windows installer expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "PORTABLE".equals(a.get("kind"))
                && "windows".equals(a.get("platform")) && "lite".equals(a.get("variant"))),
                "windows portable expected: " + artifacts);
        // macOS ships installers only (lite + bundled-JRE dmg); the portable
        // fallbacks for it are the universal web archive / fat JAR below.
        assertTrue(artifacts.stream().anyMatch(a -> "INSTALLER".equals(a.get("kind"))
                && "macos".equals(a.get("platform")) && "lite".equals(a.get("variant"))),
                "macos installer expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "INSTALLER".equals(a.get("kind"))
                && "macos".equals(a.get("platform")) && "jre".equals(a.get("variant"))),
                "macos bundled-JRE installer expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "INSTALLER".equals(a.get("kind"))
                && "linux".equals(a.get("platform"))), "linux installer expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "PORTABLE".equals(a.get("kind"))
                && "linux".equals(a.get("platform"))), "linux portable expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "web".equals(a.get("variant"))),
                "universal web archive variant expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "jar".equals(a.get("variant"))),
                "fat-JAR variant expected: " + artifacts);
    }

    /** A publisher pushes the full FengYu release matrix through the normal
     *  pipeline; kind/platform/variant are inferred from the asset filenames. */
    @Test
    @SuppressWarnings("unchecked")
    void publisherUploadsInstallerAndPortableMatrixEndToEnd() throws Exception {
        String publisherToken = AuthTestSupport.clientCredentialsToken(http(), "store-cli",
                "dev-only-cli-secret");
        String reviewerToken = AuthTestSupport.login(http(), null, "reviewer@infinia.local",
                "Password123!");

        String slug = "app-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/organizations",
                jsonAuth(publisherToken), Map.of("slug", slug, "name", "App E2E Org"),
                Map.class).getStatusCode().value());
        assertEquals(201, http().exchangeJson(HttpMethod.POST, "/api/v1/publisher/listings",
                jsonAuth(publisherToken), Map.of("namespace", slug, "slug", "host", "type",
                        "APP", "category", "Productivity", "name", "App E2E Host",
                        "summary", "installed + portable matrix e2e"), Map.class)
                .getStatusCode().value());
        String listingId = listings.findByCoordinate(
                        dev.infinia.store.contract.coordinate.InfiniaCoordinate.parse(
                                "infinia://app/" + slug + "/host"))
                .orElseThrow().id.toString();

        ResponseEntity<Map> release = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/listings/" + listingId + "/releases", jsonAuth(publisherToken),
                Map.of("version", "4.5.0", "channel", "stable"), Map.class);
        assertEquals(201, release.getStatusCode().value());
        String releaseId = (String) release.getBody().get("releaseId");

        // The FengYu release matrix: installed, portable, bundled-JRE and web builds.
        // Values are [kind, platform, arch, variant] — all inferred from the filename.
        Map<String, String[]> assets = Map.of(
                "Infinia-4.5.0-win-x64-setup.exe",
                new String[] {"INSTALLER", "windows", "x64", "lite"},
                "Infinia-4.5.0-win-x64-portable.zip",
                new String[] {"PORTABLE", "windows", "x64", "lite"},
                "Infinia-JRE-4.5.0-mac-arm64.dmg",
                new String[] {"INSTALLER", "macos", "arm64", "jre"},
                "Infinia-4.5.0-linux-x64.deb",
                new String[] {"INSTALLER", "linux", "x64", "lite"},
                "Infinia-4.5.0-web.zip",
                new String[] {"PORTABLE", "universal", "universal", "web"},
                "Infinia.jar",
                new String[] {"PORTABLE", "universal", "universal", "jar"});
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        for (Map.Entry<String, String[]> asset : assets.entrySet()) {
            // The declared size is a commitment the upload completion enforces —
            // declare the true body length ("app-binary:" + filename).
            int declaredSize = ("app-binary:" + asset.getKey())
                    .getBytes(StandardCharsets.UTF_8).length;
            ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                    "/api/v1/publisher/releases/" + releaseId + "/uploads",
                    jsonAuth(publisherToken), Map.of("filename", asset.getKey(),
                            "size", declaredSize), Map.class);
            assertEquals(201, upload.getStatusCode().value(), asset.getKey());
            // Routing metadata is inferred from the filename alone.
            assertEquals(asset.getValue()[0], upload.getBody().get("kind"), asset.getKey());
            assertEquals(asset.getValue()[1], upload.getBody().get("platform"), asset.getKey());
            assertEquals(asset.getValue()[2], upload.getBody().get("arch"), asset.getKey());
            assertEquals(asset.getValue()[3], upload.getBody().get("variant"), asset.getKey());
            String uploadUrl = (String) upload.getBody().get("uploadUrl");
            ResponseEntity<String> put = http().exchange(HttpMethod.PUT, uploadUrl, putHeaders,
                    ("app-binary:" + asset.getKey()).getBytes(StandardCharsets.UTF_8));
            assertEquals(204, put.getStatusCode().value(), asset.getKey());
        }

        // Submit without any binary is rejected earlier; with the matrix it scans clean.
        ResponseEntity<Map> submit = http().exchangeJson(HttpMethod.POST,
                "/api/v1/publisher/releases/" + releaseId + "/submit",
                Http.bearer(publisherToken), null, Map.class);
        assertEquals(202, submit.getStatusCode().value());

        // Wait for the async scan, then approve as reviewer.
        awaitStatus(publisherToken, releaseId, "IN_REVIEW");
        ResponseEntity<List> queue = http().getJson("/api/v1/reviews?status=IN_REVIEW",
                List.class, Http.bearer(reviewerToken));
        Map<String, Object> queued = null;
        for (Object item : queue.getBody()) {
            Map<?, ?> review = (Map<?, ?>) item;
            if (releaseId.equals(String.valueOf(review.get("releaseId")))) {
                queued = (Map<String, Object>) review;
                break;
            }
        }
        assertNotNull(queued, "release must enter the review queue");
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/reviews/" + queued.get("reviewId") + "/decisions",
                jsonAuth(reviewerToken), Map.of("decision", "APPROVE", "notes", "matrix ok"),
                Map.class).getStatusCode().value());

        // Published listing detail exposes the whole matrix with variants.
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/" + slug + "/host",
                Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        Map<String, Object> published = (Map<String, Object>) ((List<?>) detail.getBody()
                .get("releases")).get(0);
        assertEquals("PUBLISHED", published.get("status"));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) published
                .get("artifacts");
        assertEquals(assets.size(), artifacts.size());
        assertTrue(artifacts.stream().anyMatch(a -> "PORTABLE".equals(a.get("kind"))
                && "web".equals(a.get("variant"))));
        assertTrue(artifacts.stream().allMatch(a -> a.get("keyId") != null),
                "every APP binary carries the platform signature");

        // A specific portable artifact downloads through an artifactId-scoped ticket.
        Map<String, Object> portableZip = artifacts.stream()
                .filter(a -> "Infinia-4.5.0-win-x64-portable.zip".equals(a.get("filename")))
                .findFirst().orElseThrow();
        ResponseEntity<Map> ticket = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + releaseId + "/download-ticket?artifactId="
                        + portableZip.get("artifactId"), null, null, Map.class);
        assertEquals(200, ticket.getStatusCode().value());
        ResponseEntity<byte[]> bytes = http().getBytes(
                (String) ticket.getBody().get("url"));
        assertEquals("app-binary:Infinia-4.5.0-win-x64-portable.zip",
                new String(bytes.getBody(), StandardCharsets.UTF_8));
    }

    /** Every published release serves a sha256sum-compatible checksums.txt
     *  covering its binary artifacts (design §8.3). */
    @Test
    @SuppressWarnings("unchecked")
    void checksumsManifestMatchesPublishedArtifacts() {
        ResponseEntity<Map> detail = http().getJson("/api/v1/listings/official/fengyu-host",
                Map.class, null);
        Map<String, Object> release = ((List<Map<String, Object>>) detail.getBody()
                .get("releases")).get(0);
        String releaseId = (String) release.get("releaseId");
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) release.get("artifacts")) {
            String kind = String.valueOf(a.get("kind"));
            if ("INSTALLER".equals(kind) || "PORTABLE".equals(kind) || "PACKAGE".equals(kind)) {
                expected.put((String) a.get("filename"), (String) a.get("sha256"));
            }
        }
        assertFalse(expected.isEmpty(), "seeded host release carries binary artifacts");

        ResponseEntity<String> manifest = http().get(
                "/api/v1/releases/" + releaseId + "/checksums.txt", null);
        assertEquals(200, manifest.getStatusCode().value());
        assertTrue(manifest.getHeaders().getContentType().isCompatibleWith(
                MediaType.TEXT_PLAIN), "text/plain content type");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : manifest.getBody().split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([0-9a-f]{64})  (.+)$").matcher(line);
            assertTrue(m.matches(), "sha256sum format expected: " + line);
            String filename = m.group(2);
            assertEquals(expected.get(filename), m.group(1), "digest matches " + filename);
            seen.add(filename);
        }
        assertEquals(expected.keySet(), seen, "manifest covers every binary artifact");

        assertEquals(404, http().get(
                "/api/v1/releases/" + UUID.randomUUID() + "/checksums.txt", null)
                .getStatusCode().value());
    }

    private void awaitStatus(String token, String releaseId, String expected)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            ResponseEntity<Map> status = http().getJson(
                    "/api/v1/publisher/releases/" + releaseId, Map.class, Http.bearer(token));
            if (expected.equals(status.getBody().get("status"))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("release never reached " + expected);
    }

    /**
     * Intranet admin manual upload (the store replaces the FY-Proxy distribution
     * center): the platform admin starts an upload on the seeded CI-owned host
     * listing (ownership bypass), PUTs the package through the ticketed
     * pipeline, publishes instantly without a review round-trip, and the compat
     * mirror immediately serves the release to the desktop updater.
     */
    @Test
    @SuppressWarnings("unchecked")
    void adminManualUploadPublishesInstantlyAndFeedsTheCompatMirror() throws Exception {
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        String userToken = AuthTestSupport.login(http(), null, "user@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        byte[] zip = "admin-manual-upload-portable-zip".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> startBody = Map.of("version", "9.9.9", "channel", "stable",
                "filename", "Infinia-9.9.9-win32-x64-portable.zip", "size", zip.length);

        // A plain user must not reach the admin surface.
        assertEquals(403, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/app-releases",
                jsonAuth(userToken), startBody, Map.class).getStatusCode().value());

        ResponseEntity<Map> start = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases", jsonAuth(adminToken), startBody, Map.class);
        assertEquals(201, start.getStatusCode().value());
        Map<String, Object> started = start.getBody();
        assertEquals("PORTABLE", started.get("kind"));
        assertEquals("windows", started.get("platform"));
        String releaseId = (String) started.get("releaseId");

        // Anonymous ticketed PUT carries the package bytes.
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertEquals(204, http().exchange(HttpMethod.PUT, (String) started.get("uploadUrl"),
                putHeaders, zip).getStatusCode().value());

        // Publish immediately — the platform admin is the review decision.
        ResponseEntity<Map> published = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases/" + releaseId + "/publish", jsonAuth(adminToken),
                null, Map.class);
        assertEquals(200, published.getStatusCode().value());
        assertEquals("PUBLISHED", published.getBody().get("status"));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) published.getBody()
                .get("artifacts");
        assertEquals(1, artifacts.size());
        assertTrue(((String) artifacts.get(0).get("sha256")).matches("[0-9a-f]{64}"));

        // The compat mirror immediately serves it to the FengYu desktop updater.
        ResponseEntity<Map> mirror = http().getJson(
                "/api/v1/compat/fengyu/fengyu-releases/api/releases/latest"
                        + "?channel=windows-portable", Map.class, null);
        assertEquals(200, mirror.getStatusCode().value());
        assertEquals("v9.9.9", mirror.getBody().get("tag_name"));
        Map<String, Object> asset = ((List<Map<String, Object>>) mirror.getBody().get("assets"))
                .get(0);
        assertEquals("Infinia-9.9.9-win32-x64-portable.zip", asset.get("name"));
        assertTrue(((String) asset.get("digest")).matches("sha256:[0-9a-f]{64}"),
                "mandatory digest for the updater");
        // The mirrored ticketed URL actually serves the uploaded bytes (the
        // absolute origin carries the configured base-url, so rebase on the
        // test server before fetching).
        String downloadUrl = (String) asset.get("browser_download_url");
        ResponseEntity<byte[]> served = http().getBytes(
                downloadUrl.substring(downloadUrl.indexOf("/api/v1/blobs/")));
        assertEquals(200, served.getStatusCode().value());
        assertArrayEquals(zip, served.getBody());

        // Restore the seeded catalog state for the other tests in this class
        // (they assert the seeded 4.1.0 matrix is the latest stable release).
        assertEquals(200, http().exchange(HttpMethod.POST,
                "/api/v1/admin/releases/" + releaseId + "/yank", jsonAuth(adminToken),
                Map.of("reason", "test cleanup")).getStatusCode().value());
    }

    /**
     * The package filename is the single source of truth: version and channel
     * are inferred when omitted ({@code -beta.1} → the beta channel), a
     * pre-release never shadows the stable line in the desktop mirror, and
     * deleting a published manual release removes it from the mirror instantly.
     * Self-contained: the stable release it publishes is deleted again at the
     * end, and the leftover beta draft is deleted too.
     */
    @Test
    @SuppressWarnings("unchecked")
    void adminUploadInfersVersionAndChannelAndDeleteRemovesFromTheMirror() {
        String adminToken = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);

        // No version/channel passed — both come from the filename.
        byte[] zip = "beta-zip-bytes".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> start = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases", jsonAuth(adminToken),
                Map.of("filename", "Infinia-6.0.0-beta.1-win32-x64-portable.zip",
                        "size", zip.length),
                Map.class);
        assertEquals(201, start.getStatusCode().value());
        assertEquals("6.0.0-beta.1", start.getBody().get("version"));
        assertEquals("beta", start.getBody().get("channel"));
        String betaReleaseId = (String) start.getBody().get("releaseId");

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertEquals(204, http().exchange(HttpMethod.PUT, (String) start.getBody().get("uploadUrl"),
                putHeaders, zip).getStatusCode().value());
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases/" + betaReleaseId + "/publish", jsonAuth(adminToken),
                null, Map.class).getStatusCode().value());

        // A pre-release never shadows the stable line in the desktop mirror.
        ResponseEntity<Map> mirror = http().getJson(
                "/api/v1/compat/fengyu/fengyu-releases/api/releases/latest"
                        + "?channel=windows-portable", Map.class, null);
        assertNotEquals("v6.0.0-beta.1", mirror.getBody().get("tag_name"));

        // Deleting works on any status, 404s afterwards, and the release is gone
        // from the admin list.
        assertEquals(204, http().exchange(HttpMethod.DELETE,
                "/api/v1/admin/app-releases/" + betaReleaseId, jsonAuth(adminToken),
                null).getStatusCode().value());
        assertEquals(404, http().exchange(HttpMethod.DELETE,
                "/api/v1/admin/app-releases/" + betaReleaseId, jsonAuth(adminToken),
                null).getStatusCode().value());
        List<Map<String, Object>> listed = http().getJson("/api/v1/admin/app-releases",
                List.class, Http.bearer(adminToken)).getBody();
        assertTrue(listed.stream().noneMatch(r -> betaReleaseId.equals(r.get("releaseId"))));

        // The same flow with an inferred STABLE version: it becomes the mirror's
        // latest, and DELETE removes it from the feed immediately.
        byte[] stable = "stable-zip-bytes".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> stableStart = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases", jsonAuth(adminToken),
                Map.of("filename", "Infinia-6.0.0-win32-x64-portable.zip", "size", stable.length),
                Map.class);
        assertEquals(201, stableStart.getStatusCode().value());
        assertEquals("6.0.0", stableStart.getBody().get("version"));
        assertEquals("stable", stableStart.getBody().get("channel"));
        String stableId = (String) stableStart.getBody().get("releaseId");
        assertEquals(204, http().exchange(HttpMethod.PUT,
                (String) stableStart.getBody().get("uploadUrl"), putHeaders,
                stable).getStatusCode().value());
        assertEquals(200, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/app-releases/" + stableId + "/publish", jsonAuth(adminToken),
                null, Map.class).getStatusCode().value());
        mirror = http().getJson("/api/v1/compat/fengyu/fengyu-releases/api/releases/latest"
                + "?channel=windows-portable", Map.class, null);
        assertEquals("v6.0.0", mirror.getBody().get("tag_name"));

        assertEquals(204, http().exchange(HttpMethod.DELETE,
                "/api/v1/admin/app-releases/" + stableId, jsonAuth(adminToken),
                null).getStatusCode().value());
        mirror = http().getJson("/api/v1/compat/fengyu/fengyu-releases/api/releases/latest"
                + "?channel=windows-portable", Map.class, null);
        assertNotEquals("v6.0.0", mirror.getBody().get("tag_name"),
                "the deleted release leaves the mirror immediately");
    }
}
