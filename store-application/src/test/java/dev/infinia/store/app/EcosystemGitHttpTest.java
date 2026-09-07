package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.Git;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only smart-HTTP serving of the exported ecosystem repos (audit 3.4):
 * with {@code store.export.git-public-base} configured, the CLAUDE marketplace
 * carries http(s) clone URLs that work from remote hosts; JGit's GitServlet
 * serves anonymous clone/fetch under /git/** and refuses every push.
 *
 * <p>The configured base deliberately points at an external origin (the port is
 * not known before the context starts); the clone then rewrites it onto the
 * local server, which exercises exactly the repo name the entry advertises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "store.export.git-public-base=https://store.example.com/git")
@ActiveProfiles("test")
class EcosystemGitHttpTest {

    @LocalServerPort
    int port;

    @TempDir
    Path cloneTarget;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportedMarketplaceEntriesCarryCloneableHttpUrls() throws Exception {
        ResponseEntity<Map> marketplace = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        assertEquals(200, marketplace.getStatusCode().value());
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) marketplace.getBody().get("plugins");
        Map<String, Object> calendar = plugins.stream()
                .filter(p -> "official-calendar".equals(p.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no calendar entry in " + plugins));
        String url = (String) ((Map<String, Object>) calendar.get("source")).get("url");
        assertTrue(url.startsWith("https://store.example.com/git/"),
                "entry must carry the configured public base: " + url);
        assertTrue(url.endsWith(".git"), url);
        String localUrl = "http://localhost:" + port
                + url.substring("https://store.example.com".length());

        // A real anonymous clone through the smart protocol: GET info/refs
        // (service=git-upload-pack) + POST git-upload-pack.
        try (Git git = Git.cloneRepository().setURI(localUrl)
                .setDirectory(cloneTarget.resolve("calendar").toFile()).call()) {
            String manifest = Files.readString(
                    cloneTarget.resolve("calendar/.claude-plugin/plugin.json"));
            assertTrue(manifest.contains("mcpServers"), manifest);
            assertTrue(manifest.contains("official.calendar"), manifest);
        }
    }

    @Test
    void pushIsRefusedAndUnknownRepositoriesAreNotFound() throws Exception {
        ResponseEntity<Map> marketplace = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) marketplace.getBody().get("plugins");
        String url = (String) ((Map<String, Object>) plugins.get(0).get("source")).get("url");
        String repoPath = url.substring(url.indexOf("/git/"));

        // Advertise (GET) and transfer (POST) for push are both refused: the
        // servlet disables receive-pack and the security chain denies it too
        // (anonymous denial surfaces as the form-login redirect).
        assertEquals(403, http().get(repoPath + "/info/refs?service=git-receive-pack", null)
                .getStatusCode().value(), "receive-pack advertisement must be refused");
        HttpHeaders gitHeaders = new HttpHeaders();
        gitHeaders.set("Content-Type", "application/x-git-receive-pack-request");
        ResponseEntity<String> push = http().exchange(HttpMethod.POST,
                repoPath + "/git-receive-pack", gitHeaders, null);
        assertTrue(push.getStatusCode().is3xxRedirection()
                        || push.getStatusCode().value() == 403,
                "push POST must never succeed: " + push.getStatusCode());

        // Unknown/traversal repo names never resolve — the resolver only serves
        // exported dirs (containers may answer encoded slashes with 400).
        assertEquals(404, http().get("/git/does-not-exist.git/info/refs?service=git-upload-pack",
                null).getStatusCode().value());
        assertTrue(http().get(
                "/git/..%2F..%2Fetc%2Fpasswd/info/refs?service=git-upload-pack", null)
                .getStatusCode().value() >= 400, "traversal must not resolve");
    }
}
