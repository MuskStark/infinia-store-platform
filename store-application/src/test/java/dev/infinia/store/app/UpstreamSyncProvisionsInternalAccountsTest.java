package dev.infinia.store.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-style upstream bootstrap: a deployment with seeding disabled
 * (production seeds nothing — demo credentials are public knowledge) must
 * still aggregate upstreams. The sync provisions its internal credential-less
 * machine accounts itself; before that fix it aborted with "CI publisher
 * account missing (seed required)" and the status page showed the default
 * SkillHub source permanently degraded. Runs against its own empty H2
 * database; the local HTTP server stands in for the upstream marketplace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:store-upstream-bootstrap;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "store.seed.enabled=false",
                "store.upstream.defaults.enabled=false"})
@ActiveProfiles("test")
class UpstreamSyncProvisionsInternalAccountsTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository upstreams;

    private HttpServer upstream;

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncSucceedsOnAnEmptyDeploymentAndProvisionsTheMachineAccounts() throws Exception {
        startUpstream();
        // The empty deployment's admin (first registration owns the instance).
        http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register", json(null),
                Map.of("email", "owner@infinia.local", "password", "OwnerPass123!",
                        "displayName", "Owner"),
                Map.class);
        String adminToken = AuthTestSupport.login(http(), null, "owner@infinia.local",
                "OwnerPass123!");

        ResponseEntity<Map> created = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/upstreams", json(adminToken),
                Map.of("name", "boot-" + UUID.randomUUID().toString().substring(0, 6),
                        "marketplaceUrl", "http://127.0.0.1:"
                                + upstream.getAddress().getPort() + "/marketplace.json",
                        "targetNamespace", "claude",
                        "adapterType", "CLAUDE_MARKETPLACE"),
                Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "sync must succeed without seeded accounts: " + created.getBody());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) created.getBody()).get("lastSyncOk"),
                "body: " + created.getBody());

        // The aggregated skill is published and visible through the host surface.
        ResponseEntity<List> skills = http().getJson(
                "/api/v1/compat/fengyu/skills-catalog", List.class, null);
        assertTrue(skills.getBody().stream().anyMatch(s ->
                        "claude.example-skill".equals(((Map<?, ?>) s).get("id"))),
                "aggregated skill must reach the catalog");

        // The machine accounts exist, credential-less: they cannot log in.
        ResponseEntity<Map> rejected = http().exchangeJson(HttpMethod.POST,
                "/api/v1/auth/login", json(null),
                Map.of("email", "ci@infinia.local", "password", "Password123!"),
                Map.class);
        assertNotEquals(HttpStatus.OK, rejected.getStatusCode(),
                "the provisioned machine account must not be loginable");
    }

    private Http http() {
        return new Http(port);
    }

    private static HttpHeaders json(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Minimal stand-in for the official Claude skills marketplace. */
    private void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String repoUrl = "http://127.0.0.1:" + "PORTHOLDER/repo.tar.gz";
        byte[] marketplace = ("{\"plugins\":[{\"name\":\"Example Skill\","
                + "\"description\":\"Bootstrap probe skill.\",\"version\":\"1.0.0\","
                + "\"source\":{\"source\":\"url\",\"url\":\"" + repoUrl + "\"}}]}")
                        .replace("PORTHOLDER", String.valueOf(upstream.getAddress().getPort()))
                        .getBytes(StandardCharsets.UTF_8);
        byte[] skillMd = ("---\nname: example-skill\ndescription: Bootstrap probe skill.\n"
                + "version: 1.0.0\n---\n# Example Skill\nBody.")
                        .getBytes(StandardCharsets.UTF_8);
        byte[] helper = "print('helper')".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> repo = new java.util.LinkedHashMap<>();
        repo.put("repo-HEAD/example-skill/SKILL.md", skillMd);
        repo.put("repo-HEAD/example-skill/scripts/helper.py", helper);
        java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream compressor = new java.util.zip.GZIPOutputStream(gz)) {
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
            exchange.getResponseHeaders().set("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, gz.toByteArray().length);
            exchange.getResponseBody().write(gz.toByteArray());
            exchange.close();
        });
        upstream.start();
    }
}
