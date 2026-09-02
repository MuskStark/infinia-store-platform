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
 * distribution matrix per platform, variant routing on the signed update feed
 * (design §8.4) and per-artifact download tickets.
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

    /** The seeded FengYu host release must expose installer AND portable
     *  distributions for every desktop platform plus the universal variants. */
    @Test
    @SuppressWarnings("unchecked")
    void seededHostMatrixCoversInstallersAndPortablesPerPlatform() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=windows&arch=x64"
                        + "&installId=matrix-probe", Map.class, null);
        assertEquals(200, feed.getStatusCode().value());
        assertEquals("4.1.0", feed.getBody().get("latestVersion"));
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) feed.getBody()
                .get("artifacts");
        assertTrue(artifacts.stream().anyMatch(a -> "installer".equals(a.get("kind"))
                && "windows".equals(a.get("platform")) && "lite".equals(a.get("variant"))),
                "windows installer expected: " + artifacts);
        assertTrue(artifacts.stream().anyMatch(a -> "portable".equals(a.get("kind"))
                && "windows".equals(a.get("platform")) && "lite".equals(a.get("variant"))),
                "windows portable expected: " + artifacts);

        for (String[] osArch : new String[][] {{"macos", "arm64"}, {"linux", "x64"}}) {
            ResponseEntity<Map> osFeed = http().getJson(
                    "/api/v1/updates/app?current=4.0.0&channel=stable&os=" + osArch[0]
                            + "&arch=" + osArch[1] + "&installId=matrix-probe", Map.class, null);
            List<Map<String, Object>> osArtifacts = (List<Map<String, Object>>) osFeed.getBody()
                    .get("artifacts");
            assertTrue(osArtifacts.stream().anyMatch(a -> "installer".equals(a.get("kind"))),
                    osArch[0] + " installer expected: " + osArtifacts);
            assertTrue(osArtifacts.stream().anyMatch(a -> "portable".equals(a.get("kind"))),
                    osArch[0] + " portable expected: " + osArtifacts);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateFeedFiltersByModeAndVariant() {
        // Installer-only feed for macOS ARM64.
        ResponseEntity<Map> installer = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64"
                        + "&mode=installer&installId=filter-probe", Map.class, null);
        List<Map<String, Object>> installerArtifacts =
                (List<Map<String, Object>>) installer.getBody().get("artifacts");
        assertFalse(installerArtifacts.isEmpty());
        assertTrue(installerArtifacts.stream().allMatch(a -> "installer".equals(a.get("kind"))),
                "mode=installer must not mix in portables: " + installerArtifacts);

        // Portable-only feed, same platform.
        ResponseEntity<Map> portable = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64"
                        + "&mode=portable&installId=filter-probe", Map.class, null);
        List<Map<String, Object>> portableArtifacts =
                (List<Map<String, Object>>) portable.getBody().get("artifacts");
        assertTrue(portableArtifacts.stream().allMatch(a -> "portable".equals(a.get("kind"))),
                "mode=portable must not mix in installers: " + portableArtifacts);

        // variant=jre selects the bundled-JRE macOS dmg only.
        ResponseEntity<Map> jre = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64"
                        + "&mode=installer&variant=jre&installId=filter-probe", Map.class, null);
        List<Map<String, Object>> jreArtifacts =
                (List<Map<String, Object>>) jre.getBody().get("artifacts");
        assertEquals(1, jreArtifacts.size(), "exactly one JRE dmg expected: " + jreArtifacts);
        assertEquals("jre", jreArtifacts.get(0).get("variant"));
        assertEquals("installer", jreArtifacts.get(0).get("kind"));

        // Universal portable web archive and fat-JAR variants.
        ResponseEntity<Map> web = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=linux&arch=x64"
                        + "&mode=portable&variant=web&installId=filter-probe", Map.class, null);
        List<Map<String, Object>> webArtifacts =
                (List<Map<String, Object>>) web.getBody().get("artifacts");
        assertEquals(1, webArtifacts.size(), "universal web archive matches any os: "
                + webArtifacts);
        assertEquals("web", webArtifacts.get(0).get("variant"));
        assertEquals("universal", webArtifacts.get(0).get("platform"));

        ResponseEntity<Map> jar = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=windows&arch=x64"
                        + "&mode=portable&variant=jar&installId=filter-probe", Map.class, null);
        assertEquals("Infinia.jar",
                ((List<Map<String, Object>>) jar.getBody().get("artifacts")).get(0)
                        .get("filename"));

        // Unknown mode is rejected, not silently treated as "any".
        assertEquals(400, http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=macos&arch=arm64"
                        + "&mode=flavor&installId=filter-probe", Map.class, null)
                .getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void feedArtifactUrlServesSignedBytes() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=linux&arch=x64"
                        + "&mode=portable&variant=web&installId=download-probe", Map.class, null);
        Map<String, Object> artifact = ((List<Map<String, Object>>) feed.getBody()
                .get("artifacts")).get(0);
        String url = (String) artifact.get("url");
        assertTrue(url.startsWith("http://"), "feed urls are absolute: " + url);
        String pathAndQuery = url.replaceFirst("^http://[^/]+", "");
        ResponseEntity<byte[]> bytes = http().getBytes(pathAndQuery);
        assertEquals(200, bytes.getStatusCode().value());
        assertTrue(bytes.getBody().length > 0);
        assertEquals(dev.infinia.store.scanner.Ed25519Signer.sha256Hex(bytes.getBody()),
                artifact.get("sha256"), "served bytes must match the advertised digest");
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
            ResponseEntity<Map> upload = http().exchangeJson(HttpMethod.POST,
                    "/api/v1/publisher/releases/" + releaseId + "/uploads",
                    jsonAuth(publisherToken), Map.of("filename", asset.getKey(),
                            "size", 64), Map.class);
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

    /** The feed advertises the operator-configured support floor, not a code constant. */
    @Test
    void feedAdvertisesConfiguredMinimumSupportedVersion() {
        ResponseEntity<Map> feed = http().getJson(
                "/api/v1/updates/app?current=4.0.0&channel=stable&os=windows&arch=x64"
                        + "&installId=floor-probe", Map.class, null);
        assertEquals(200, feed.getStatusCode().value());
        assertEquals("3.9.0", feed.getBody().get("minimumSupportedVersion"),
                "minimumSupportedVersion must come from store.app-minimum-supported-version");
        // Even below the floor the update stays non-mandatory (design §8.4).
        assertEquals(false, feed.getBody().get("mandatory"));
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
}
