package dev.infinia.store.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Default upstream seeding (aggregation plan §3.1): a deployment boots with the
 * SkillHub source already registered and indexed — no manual admin action —
 * and re-boots neither duplicate the row nor re-sync an unchanged catalog.
 * Runs against an isolated H2 database so the seeded listings never leak into
 * the shared test catalog; a local stub stands in for api.skillhub.cn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:upstream-defaults-bootstrap;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "store.upstream.defaults.enabled=true"
})
class UpstreamDefaultsBootstrapTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.app.upstream.UpstreamCatalogBootstrap bootstrap;

    @Autowired
    dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository upstreams;

    private static final AtomicInteger metadataRequests = new AtomicInteger();
    private static HttpServer skillhub;

    @DynamicPropertySource
    static void stubSkillhub(DynamicPropertyRegistry registry) {
        skillhub = startSkillHubStub();
        registry.add("store.upstream.defaults.skillhub-url",
                () -> "http://127.0.0.1:" + skillhub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        if (skillhub != null) {
            skillhub.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootsWithSkillHubSeededIndexedAndIdempotent() throws Exception {
        // The default source exists, already indexed at boot, with provenance.
        String admin = AuthTestSupport.login(http(), null, "admin@infinia.local",
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
        HttpHeaders auth = Http.bearer(admin);
        auth.setContentType(MediaType.APPLICATION_JSON);
        List<Map<String, Object>> sources = (List<Map<String, Object>>) http()
                .getJson("/api/v1/admin/upstreams", List.class, auth).getBody();
        Map<String, Object> seeded = sources.stream()
                .filter(s -> "SkillHub (WorkBuddy)".equals(s.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("default missing: " + sources));
        assertEquals("SKILLHUB_REGISTRY", seeded.get("adapterType"));
        assertEquals("skillhub", seeded.get("targetNamespace"));
        assertEquals(Boolean.TRUE, seeded.get("lastSyncOk"), "body: " + seeded);
        int requestsAfterBoot = metadataRequests.get();
        assertTrue(requestsAfterBoot >= 1, "boot must have indexed the catalog");

        // The WorkBuddy skill is live on the host-facing catalog under its
        // dedicated namespace, versioned from the upstream catalog row.
        List<Map<String, Object>> skills = (List<Map<String, Object>>) http()
                .getJson("/api/v1/compat/fengyu/skills-catalog", List.class, null).getBody();
        Map<String, Object> entry = skills.stream()
                .filter(s -> "skillhub.wb-boot-skill".equals(s.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("skill missing: " + skills));
        assertEquals("1.0.0", entry.get("version"));
        assertEquals("skillhub", entry.get("author"));

        // A second boot cycle (re-invoked listener) neither duplicates the row
        // nor re-indexes the already-successful source.
        bootstrap.indexNeverSyncedSources();
        assertEquals(1, upstreams.findAll().stream()
                .filter(s -> "SkillHub (WorkBuddy)".equals(s.name())).count(),
                "seeding is idempotent per name");
        assertEquals(requestsAfterBoot, metadataRequests.get(),
                "a synced source must not be re-indexed");
    }

    private static HttpServer startSkillHubStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] catalog = ("{\"code\":0,\"message\":\"success\",\"data\":{\"total\":1,"
                    + "\"skills\":[{\"slug\":\"wb-boot-skill\",\"source\":\"official\","
                    + "\"name\":\"Boot Skill\",\"description\":\"Seeded at boot\","
                    + "\"description_zh\":\"启动即聚合\",\"version\":\"1.0.0\","
                    + "\"homepage\":\"http://127.0.0.1:1/wb-boot-skill\","
                    + "\"tags\":[\"latest\"],\"downloads\":42}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/api/skills", exchange -> {
                metadataRequests.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, catalog.length);
                try (InputStream ignored = exchange.getRequestBody()) {
                    exchange.getResponseBody().write(catalog);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Http http() {
        return new Http(port);
    }
}
