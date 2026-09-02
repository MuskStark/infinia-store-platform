package dev.infinia.store.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Admin remote-database configuration (需求：管理员配置连接远程数据库):
 * register endpoints with sealed credentials, probe real JDBC connectivity,
 * validate dangerous URLs are rejected, activate one as the data-source
 * override (file written for the startup post-processor) and audit everything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminRemoteDatabaseTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.app.service.RemoteDatabaseService databases;

    @Autowired
    org.springframework.core.env.Environment environment;

    Http http() {
        return new Http(port);
    }

    private String login(String email) {
        return AuthTestSupport.login(http(), null, email,
                dev.infinia.store.app.seed.SeedData.DEMO_PASSWORD);
    }

    private HttpHeaders json(String token) {
        HttpHeaders headers = token == null ? new HttpHeaders() : Http.bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Path overrideFile() {
        return Path.of(environment.getProperty("store.remote-datasource-file"));
    }

    @AfterEach
    void cleanup() {
        String admin = login("admin@infinia.local");
        http().exchange(HttpMethod.POST, "/api/v1/admin/databases/deactivate",
                Http.bearer(admin), null);
        for (var row : databases.list()) {
            http().exchange(HttpMethod.DELETE,
                    "/api/v1/admin/databases/" + row.databaseId(), Http.bearer(admin), null);
        }
    }

    private Map<String, Object> create(String token, String name, String url, String user,
            String password) {
        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases", json(token),
                Map.of("name", name, "jdbcUrl", url, "username", user, "password", password),
                Map.class);
        assertEquals(201, response.getStatusCode().value(), () -> String.valueOf(response.getBody()));
        return response.getBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRegistersListsAndNeverLeaksPasswords() {
        String admin = login("admin@infinia.local");
        Map<String, Object> created = create(admin, "reporting-h2",
                "jdbc:h2:mem:admin-db-a;DB_CLOSE_DELAY=-1", "sa", "secret-pass");

        assertNotNull(created.get("databaseId"));
        assertEquals("reporting-h2", created.get("name"));
        assertFalse((Boolean) created.get("enabled"));
        assertFalse(String.valueOf(created).contains("secret-pass"),
                "the password must never appear in API responses");

        ResponseEntity<List> list = http().getJson("/api/v1/admin/databases", List.class,
                Http.bearer(admin));
        assertEquals(200, list.getStatusCode().value());
        assertTrue(list.getBody().stream().anyMatch(d ->
                "reporting-h2".equals(((Map<?, ?>) d).get("name"))));
    }

    @Test
    void nonAdminsAreForbidden() {
        String user = login("user@infinia.local");
        assertEquals(403, http().getJson("/api/v1/admin/databases", List.class,
                Http.bearer(user)).getStatusCode().value());
        assertEquals(403, http().getJson("/api/v1/admin/databases/status", Map.class,
                Http.bearer(user)).getStatusCode().value());
        assertEquals(403, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases",
                json(user), Map.of("name", "x", "jdbcUrl", "jdbc:h2:mem:x",
                        "username", "sa", "password", "p"), Map.class)
                .getStatusCode().value());
    }

    @Test
    void dangerousAndUnsupportedJdbcUrlsAreRejected() {
        String admin = login("admin@infinia.local");
        // H2 INIT= is a code-execution vector — must never reach the driver.
        assertEquals(400, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases",
                json(admin), Map.of("name", "evil", "username", "sa", "password", "p",
                        "jdbcUrl", "jdbc:h2:mem:x;INIT=CREATE ALIAS T FOR 'java.lang.System.exit'"),
                Map.class).getStatusCode().value());
        // RUNSCRIPT variant.
        assertEquals(400, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases",
                json(admin), Map.of("name", "evil2", "username", "sa", "password", "p",
                        "jdbcUrl", "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'x.sql'"),
                Map.class).getStatusCode().value());
        // Drivers the platform does not ship.
        assertEquals(400, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases",
                json(admin), Map.of("name", "mysql", "username", "root", "password", "p",
                        "jdbcUrl", "jdbc:mysql://db.example.com:3306/store"),
                Map.class).getStatusCode().value());
        // Duplicate names are rejected.
        create(admin, "dup-check", "jdbc:h2:mem:admin-db-dup", "sa", "p1");
        assertEquals(400, http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases",
                json(admin), Map.of("name", "dup-check", "jdbcUrl", "jdbc:h2:mem:other",
                        "username", "sa", "password", "p"),
                Map.class).getStatusCode().value());
    }

    @Test
    void testProbesRealConnectivityBothWays() {
        String admin = login("admin@infinia.local");
        Map<String, Object> ok = create(admin, "probe-ok",
                "jdbc:h2:mem:admin-db-ok;DB_CLOSE_DELAY=-1", "sa", "db-pass-1");
        ResponseEntity<Map> good = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases/" + ok.get("databaseId") + "/test", json(admin),
                null, Map.class);
        assertEquals(200, good.getStatusCode().value());
        assertEquals(true, good.getBody().get("ok"));
        assertEquals("H2", good.getBody().get("productName"));
        assertNotNull(good.getBody().get("productVersion"));

        Map<String, Object> bad = create(admin, "probe-bad",
                "jdbc:postgresql://127.0.0.1:1/unreachable", "nobody", "nope");
        ResponseEntity<Map> failed = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases/" + bad.get("databaseId") + "/test", json(admin),
                null, Map.class);
        assertEquals(200, failed.getStatusCode().value());
        assertEquals(false, failed.getBody().get("ok"));
        assertNotNull(failed.getBody().get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activationRequiresLiveProbeWritesOverrideAndIsExclusive() throws Exception {
        String admin = login("admin@infinia.local");

        // Activation of an unreachable endpoint is refused outright.
        Map<String, Object> bad = create(admin, "act-bad",
                "jdbc:postgresql://127.0.0.1:1/unreachable", "nobody", "nope");
        assertEquals(400, http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases/" + bad.get("databaseId") + "/activation",
                json(admin), Map.of("enabled", true), Map.class).getStatusCode().value());

        Map<String, Object> good = create(admin, "act-good",
                "jdbc:h2:mem:admin-db-act;DB_CLOSE_DELAY=-1", "sa", "db-pass-1");
        ResponseEntity<Map> activated = http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases/" + good.get("databaseId") + "/activation",
                json(admin), Map.of("enabled", true), Map.class);
        assertEquals(200, activated.getStatusCode().value());
        assertEquals(true, activated.getBody().get("enabled"));

        // The override file exists for the startup post-processor.
        Path file = overrideFile();
        assertTrue(Files.exists(file), "override file written: " + file);
        String contents = Files.readString(file);
        assertTrue(contents.contains("jdbc:h2:mem:admin-db-act"),
                "override carries the activated URL; got: " + contents);
        assertTrue(contents.contains("spring.datasource.url"));
        assertFalse(contents.contains("password=\n"));

        // Status reports the live override.
        ResponseEntity<Map> status = http().getJson("/api/v1/admin/databases/status",
                Map.class, Http.bearer(admin));
        assertEquals(true, status.getBody().get("remoteOverrideActive"));
        assertEquals("act-good", status.getBody().get("overrideName"));
        assertNotNull(status.getBody().get("productName"));
        assertFalse(String.valueOf(status.getBody().get("url")).contains("nope"));

        // Activating another connection disables the previous one.
        Map<String, Object> second = create(admin, "act-second",
                "jdbc:h2:mem:admin-db-act2;DB_CLOSE_DELAY=-1", "sa", "db-pass-1");
        http().exchangeJson(HttpMethod.POST,
                "/api/v1/admin/databases/" + second.get("databaseId") + "/activation",
                json(admin), Map.of("enabled", true), Map.class);
        ResponseEntity<List> list = http().getJson("/api/v1/admin/databases", List.class,
                Http.bearer(admin));
        long enabledCount = ((List<Map<String, Object>>) (List<?>) list.getBody()).stream()
                .filter(d -> Boolean.TRUE.equals(d.get("enabled"))).count();
        assertEquals(1, enabledCount, "exactly one active override");

        // Deactivate drops the override file and flips status back.
        assertEquals(204, http().exchange(HttpMethod.POST,
                "/api/v1/admin/databases/deactivate", Http.bearer(admin), null)
                .getStatusCode().value());
        assertFalse(Files.exists(file));
        status = http().getJson("/api/v1/admin/databases/status", Map.class,
                Http.bearer(admin));
        assertEquals(false, status.getBody().get("remoteOverrideActive"));

        // All the admin actions are audited.
        ResponseEntity<List> audit = http().getJson("/api/v1/admin/audit-events?limit=100",
                List.class, Http.bearer(admin));
        List<String> actions = audit.getBody().stream()
                .map(e -> String.valueOf(((Map<?, ?>) e).get("action"))).toList();
        assertTrue(actions.contains("database.create"));
        assertTrue(actions.contains("database.activate"));
        assertTrue(actions.contains("database.deactivate"));
    }

    @Test
    void updateKeepsPasswordWhenBlankAndRefreshesOverride() throws Exception {
        String admin = login("admin@infinia.local");
        Map<String, Object> created = create(admin, "upd-check",
                "jdbc:h2:mem:admin-db-upd;DB_CLOSE_DELAY=-1", "sa", "first-pass");
        String id = (String) created.get("databaseId");

        // Activate first so the override must track updates.
        http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases/" + id + "/activation",
                json(admin), Map.of("enabled", true), Map.class);

        ResponseEntity<Map> updated = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/databases/" + id, json(admin),
                Map.of("jdbcUrl", "jdbc:h2:mem:admin-db-upd2;DB_CLOSE_DELAY=-1"), Map.class);
        assertEquals(200, updated.getStatusCode().value());
        assertEquals("jdbc:h2:mem:admin-db-upd2;DB_CLOSE_DELAY=-1",
                updated.getBody().get("jdbcUrl"));
        // The still-enabled row refreshed the override file.
        assertTrue(Files.readString(overrideFile()).contains("admin-db-upd2"));

        // Password rotation is sealed and never echoed.
        ResponseEntity<Map> rotated = http().exchangeJson(HttpMethod.PUT,
                "/api/v1/admin/databases/" + id, json(admin), Map.of("password", "second-pass"),
                Map.class);
        assertEquals(200, rotated.getStatusCode().value());
        assertFalse(String.valueOf(rotated.getBody()).contains("second-pass"));
    }

    @Test
    void deletingTheActiveConnectionAlsoDropsTheOverride() throws Exception {
        String admin = login("admin@infinia.local");
        Map<String, Object> created = create(admin, "del-active",
                "jdbc:h2:mem:admin-db-del;DB_CLOSE_DELAY=-1", "sa", "db-pass-1");
        String id = (String) created.get("databaseId");
        http().exchangeJson(HttpMethod.POST, "/api/v1/admin/databases/" + id + "/activation",
                json(admin), Map.of("enabled", true), Map.class);
        assertTrue(Files.exists(overrideFile()));

        assertEquals(204, http().exchange(HttpMethod.DELETE,
                "/api/v1/admin/databases/" + id, Http.bearer(admin), null)
                .getStatusCode().value());
        assertFalse(Files.exists(overrideFile()), "override dropped with its connection");
    }
}
