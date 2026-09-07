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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import dev.infinia.store.domain.model.UpstreamSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upstream aggregation (design §2.1): the store mirrors an external Claude
 * marketplace into its own catalog through the full publish pipeline, so hosts
 * configure only the store. A local HTTP server stands in for the upstream
 * (marketplace.json + skill repository tarball). The sync materializes every
 * imported payload into blob storage (audit 3.1) — downloads, catalogs and
 * tickets all carry real, verifiable digests.
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
    /** The full-pipeline test asserts recovery from a transient metadata 429. */
    private boolean failFirstMetadataRequest = false;

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
        failFirstMetadataRequest = true;
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

        // Registration immediately indexes the source; no separate admin action
        // is required before the upstream item becomes visible.
        assertEquals(Boolean.TRUE, created.getBody().get("lastSyncOk"));
        assertEquals(2, metadataRequests.get(),
                "transient metadata failures should recover without admin intervention");
        assertEquals(1, payloadRequests.get(),
                "the sync materializes the referenced repository artifact exactly once");
        assertTrue(upstreamTempDirectories().isEmpty(),
                "materialization workspaces must be deleted after the sync");

        // A manual sync after registration is idempotent.
        ResponseEntity<Map> first = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(adminToken), null,
                Map.class);
        assertEquals(200, first.getStatusCode().value());
        assertEquals(0, ((Number) first.getBody().get("imported")).intValue(),
                "body: " + first.getBody());
        assertEquals(1, ((Number) first.getBody().get("skipped")).intValue());
        assertEquals(0, ((Number) first.getBody().get("failed")).intValue());
        assertEquals(1, payloadRequests.get(),
                "unchanged content must not be fetched or re-published");

        // The aggregated skill reaches both host-facing skill surfaces, versioned
        // from the upstream SKILL.md frontmatter — with a real, signed digest.
        ResponseEntity<List> skills = http().getJson("/api/v1/compat/fengyu/skills-catalog",
                List.class, null);
        Map<String, Object> aggregated = (Map<String, Object>) skills.getBody().stream()
                .filter(s -> "claude.example-skill".equals(((Map<?, ?>) s).get("id")))
                .findFirst().orElse(null);
        assertNotNull(aggregated, "skill missing from catalog");
        assertEquals("2.0.0", aggregated.get("version"));
        assertEquals("claude", aggregated.get("author"));
        String sha256 = (String) aggregated.get("sha256");
        assertEquals(64, sha256.length(),
                "upstream entries must expose the materialized payload digest: " + aggregated);
        assertNotNull(aggregated.get("signature"), "approval signs the stored bytes");
        assertNotNull(aggregated.get("keyId"));

        ResponseEntity<Map> detail = http().getJson(
                "/api/v1/listings/claude/example-skill", Map.class, null);
        assertEquals(200, detail.getStatusCode().value());
        Map<String, Object> provenance = (Map<String, Object>) detail.getBody().get("upstream");
        assertNotNull(provenance, "upstream provenance missing from listing detail");
        assertEquals(upstreamName, provenance.get("sourceName"));
        assertEquals("2.0.0", provenance.get("upstreamVersion"));
        assertEquals("MATERIALIZED_BLOB", provenance.get("deliveryMode"));
        assertTrue(String.valueOf(provenance.get("sourceUrl")).endsWith("/repo.tar.gz"));
        assertNotNull(provenance.get("metadataSha256"));

        // The materialized package is a durable store blob, not a pass-through key.
        String downloadUrl = String.valueOf(aggregated.get("downloadUrl"));
        String blobKey = downloadUrl.substring(downloadUrl.indexOf("/blobs/") + 7,
                downloadUrl.indexOf('?'));
        assertTrue(blobKey.startsWith("sha256/"), "content-addressed blob key: " + blobKey);
        assertTrue(blobs.exists(blobKey), "materialized upstream package must exist on disk");

        ResponseEntity<Map> ecosystem = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) ecosystem.getBody().get("plugins");
        assertFalse(plugins.stream().anyMatch(p ->
                        "claude-example-skill".equals(p.get("name"))),
                "aggregated upstream entries must not enter the local git exports: " + plugins);

        // A user download serves the stored blob — verifiable, sized, counted.
        long downloadsBefore = ((Number) detail.getBody().get("downloads")).longValue();
        var download = http().getBytes(downloadUrl.replaceFirst("^http://[^/]+", ""));
        assertEquals(200, download.getStatusCode().value(),
                () -> new String(download.getBody(), StandardCharsets.UTF_8));
        assertEquals(sha256, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(download.getBody())),
                "downloaded bytes match the advertised sha256");
        assertEquals(download.getBody().length,
                Integer.parseInt(download.getHeaders().getFirst("Content-Length")),
                "blob downloads carry Content-Length");
        Map<String, dev.infinia.store.scanner.SafeZip.ExtractedFile> packed =
                dev.infinia.store.scanner.SafeZip.extract(
                        new java.io.ByteArrayInputStream(download.getBody()),
                        dev.infinia.store.scanner.SafeZip.Limits.defaults());
        assertTrue(packed.containsKey("manifest.json"), "FengYu manifest added by the packer");
        assertTrue(packed.containsKey("SKILL.md"), "upstream skill retained in package");
        assertTrue(packed.containsKey("scripts/helper.py"), "skill resources retained");
        assertEquals(1, payloadRequests.get(),
                "downloads serve the stored blob without refetching the upstream");
        assertTrue(awaitDownloads("claude", "example-skill", downloadsBefore + 1),
                "a completed artifact download must increment the listing counter");

        // Second sync with unchanged content is idempotent.
        ResponseEntity<Map> second = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams/" + upstreamId + "/sync", jsonAuth(adminToken), null,
                Map.class);
        assertEquals(0, ((Number) second.getBody().get("imported")).intValue());
        assertEquals(1, ((Number) second.getBody().get("skipped")).intValue());
    }

    /**
     * Legacy rows persisted by the pre-materialization sync keep their virtual
     * {@code upstream/<uuid>} blob key. Requesting a download ticket for one
     * upgrades it on demand: fetch, scan, store, platform-sign — the ticket
     * then carries a real digest and the release serves the stored blob from
     * then on (audit 3.1: never issue digest-less tickets).
     */
    @Test
    @SuppressWarnings("unchecked")
    void ticketForLegacyVirtualUpstreamArtifactMaterializesItOnDemand() throws Exception {
        startUpstream("3.0.0", "Legacy aggregated skill.");
        var fixtures = legacyVirtualRow("legacy-" + UUID.randomUUID().toString().substring(0, 6));

        ResponseEntity<Map> ticket = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + fixtures.get("releaseId") + "/download-ticket",
                null, null, Map.class);
        assertEquals(200, ticket.getStatusCode().value(), "body: " + ticket.getBody());
        String sha256 = (String) ticket.getBody().get("sha256");
        assertEquals(64, sha256.length(), "ticket must carry a real digest");
        assertNotNull(ticket.getBody().get("signature"), "ticket must be platform-signed");
        assertNotNull(ticket.getBody().get("keyId"));
        long size = ((Number) ticket.getBody().get("size")).longValue();
        assertTrue(size > 0, "ticket must carry the real size");

        String url = (String) ticket.getBody().get("url");
        var download = http().getBytes(url);
        assertEquals(200, download.getStatusCode().value());
        assertEquals(sha256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(download.getBody())));
        assertEquals(download.getBody().length, Integer.parseInt(
                download.getHeaders().getFirst("Content-Length")));
        assertEquals(size, download.getBody().length);

        // The release row now points at the stored blob; a second ticket is
        // served from storage without touching the upstream again.
        int fetchesAfterUpgrade = payloadRequests.get();
        ResponseEntity<Map> again = http().exchangeJson(HttpMethod.POST,
                "/api/v1/releases/" + fixtures.get("releaseId") + "/download-ticket",
                null, null, Map.class);
        assertEquals(sha256, again.getBody().get("sha256"));
        assertEquals(fetchesAfterUpgrade, payloadRequests.get(),
                "an upgraded artifact must never be refetched");
        String storedKey = String.valueOf(again.getBody().get("url"));
        storedKey = storedKey.substring(storedKey.indexOf("/blobs/") + 7,
                storedKey.indexOf('?'));
        assertTrue(storedKey.startsWith("sha256/"), "upgraded key: " + storedKey);
        assertTrue(blobs.exists(storedKey), "upgraded artifact is a durable blob");
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
            if (failFirstMetadataRequest && metadataRequests.incrementAndGet() == 1) {
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

    // ---- legacy-row fixture ----

    @Autowired
    dev.infinia.store.app.service.PublisherService publisher;
    @Autowired
    dev.infinia.store.app.upstream.ClaudeMarketplaceAdapter claudeAdapter;
    @Autowired
    dev.infinia.store.app.upstream.RepoFetcher fetcher;
    @Autowired
    dev.infinia.store.app.upstream.UpstreamPackageBuilder packageBuilder;
    @Autowired
    dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository upstreamSources;
    @Autowired
    dev.infinia.store.domain.port.UpstreamRepositories.UpstreamItemRepository upstreamItems;
    @Autowired
    dev.infinia.store.domain.port.ReleaseRepository releases;
    @Autowired
    dev.infinia.store.domain.port.IdentityRepositories.UserRepository users;
    @Autowired
    dev.infinia.store.domain.port.IdentityRepositories.NamespaceRepository namespaces;

    /**
     * Builds the row shape the pass-through era persisted: published release
     * whose PACKAGE artifact points at the virtual {@code upstream/<uuid>} key.
     */
    private Map<String, Object> legacyVirtualRow(String namespace) throws Exception {
        var bot = users.findByEmailNormalized("ci@infinia.local").orElseThrow();
        namespaces.save(new dev.infinia.store.domain.model.Namespace(
                dev.infinia.store.domain.service.UuidV7.generate(), namespace, bot.id,
                null, false, java.time.Instant.now()));
        UpstreamSource source = new UpstreamSource(
                dev.infinia.store.domain.service.UuidV7.generate(), "legacy-" + namespace,
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/marketplace.json",
                namespace, true, null, null, null, "CLAUDE_MARKETPLACE");
        upstreamSources.save(source);
        var item = claudeAdapter.discover(source, fetcher).get(0);
        String contentSha = packageBuilder.metadataDigest(item);
        UUID itemId = UUID.randomUUID();
        upstreamItems.save(new dev.infinia.store.domain.model.UpstreamItem(itemId,
                source.id(), item.externalId(), null, item.sourceUrl(),
                item.sourcePath(), null, null, item.version(), contentSha,
                "CLAUDE_MARKETPLACE", java.time.Instant.now(), java.time.Instant.now(),
                null));

        var listing = publisher.createListing(bot.id,
                new dev.infinia.store.contract.api.PublisherDtos.CreateListingRequest(
                        namespace, "old-skill", "SKILL", "Aggregated", List.of("upstream"),
                        "stable", item.name(), item.description(), null, "en", null));
        var release = publisher.createDraftRelease(bot.id, listing,
                new dev.infinia.store.contract.api.PublisherDtos.CreateReleaseRequest(
                        "1.0.0", "stable", null, null, item.sourceUrl(), null, null, null,
                        100));
        publisher.attachVirtualArtifact(bot.id, release,
                new dev.infinia.store.domain.model.Release.ArtifactInfo(
                        UUID.randomUUID(),
                        dev.infinia.store.contract.type.ArtifactKind.PACKAGE,
                        dev.infinia.store.contract.type.Platform.UNIVERSAL,
                        dev.infinia.store.contract.type.Arch.UNIVERSAL,
                        "default", "old-skill.fys", 0, contentSha, null, null,
                        "upstream/" + itemId, "application/octet-stream"));
        release.status = dev.infinia.store.contract.type.ReleaseStatus.PUBLISHED;
        release.publishedAt = java.time.Instant.now();
        releases.save(release);
        return Map.of("releaseId", release.id.toString());
    }

    Http http() {
        return new Http(port);
    }

    private boolean awaitDownloads(String namespace, String slug, long expected)
            throws Exception {
        for (int i = 0; i < 50; i++) {
            ResponseEntity<Map> detail = http().getJson(
                    "/api/v1/listings/" + namespace + "/" + slug, Map.class, null);
            if (((Number) detail.getBody().get("downloads")).longValue() >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
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
