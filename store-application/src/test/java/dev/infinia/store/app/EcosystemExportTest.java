package dev.infinia.store.app;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLAUDE ecosystem marketplace exports skills and MCP templates as local git
 * repositories the FengYu host can clone (design §6.2, ADR-004). These tests
 * verify the served document shape and — when a system git binary exists — that
 * the exported repositories are genuinely valid git repos whose trees contain
 * the host-expected manifest layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EcosystemExportTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketplaceCoversSkillsAndMcpWithCloneableSources() throws Exception {
        ResponseEntity<Map> marketplace = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        assertEquals(200, marketplace.getStatusCode().value());
        List<Map<String, Object>> plugins =
                (List<Map<String, Object>>) marketplace.getBody().get("plugins");
        Map<String, Object> calendar = entry(plugins, "official-calendar");
        Map<String, Object> pdf = entry(plugins, "official-pdf-tools");

        String calendarUrl = sourceUrl(calendar);
        String pdfUrl = sourceUrl(pdf);
        assertTrue(calendarUrl.startsWith("file://"), "cloneable file:// URL: " + calendarUrl);
        assertTrue(pdfUrl.startsWith("file://"));

        // Valid, readable git repositories with the host-expected layout.
        Assumptions.assumeTrue(gitAvailable(), "system git not available");
        String calendarManifest = gitShow(calendarUrl, ".claude-plugin/plugin.json");
        assertTrue(calendarManifest.contains("mcpServers"), calendarManifest);
        assertTrue(calendarManifest.contains("official.calendar"), calendarManifest);
        assertTrue(calendarManifest.contains("mcp.infinia.dev"), "remote url from template");

        String pdfManifest = gitShow(pdfUrl, ".claude-plugin/plugin.json");
        assertTrue(pdfManifest.contains("\"skills\""), pdfManifest);
        String skillMd = gitShow(pdfUrl, "skills/official.pdf-tools/SKILL.md");
        assertTrue(skillMd.contains("PDF Toolkit"), skillMd);

        // Stable content → stable commit (the host pins/records resolved HEADs).
        ResponseEntity<Map> again = http().getJson(
                "/api/v1/compat/fengyu/claude-marketplace.json", Map.class, null);
        assertEquals(marketplace.getBody(), again.getBody());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(List<Map<String, Object>> plugins, String name) {
        return plugins.stream().filter(p -> name.equals(p.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no marketplace entry " + name
                        + " in " + plugins));
    }

    private static String sourceUrl(Map<String, Object> plugin) {
        return ((Map<String, Object>) plugin.get("source")).get("url").toString();
    }

    private static String gitShow(String fileUrl, String path) throws Exception {
        String gitDir = Path.of(new URI(fileUrl)).toString();
        return git(gitDir, "show", "HEAD:" + path);
    }

    private static String git(String gitDir, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "--git-dir";
        command[2] = gitDir;
        System.arraycopy(args, 0, command, 3, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            output = out.toString();
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("git failed (" + code + "): " + output);
        }
        return output;
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
