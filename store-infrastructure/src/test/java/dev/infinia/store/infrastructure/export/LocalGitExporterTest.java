package dev.infinia.store.infrastructure.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGitExporterTest {

    @TempDir
    Path tmp;

    @Test
    void unchangedContentKeepsCommitSha() throws Exception {
        LocalGitExporter exporter = new LocalGitExporter(tmp.toString(), "");
        String first = exporter.export("demo",
                Map.of("a.txt", "hello".getBytes()), "initial");
        String second = exporter.export("demo",
                Map.of("a.txt", "hello".getBytes()), "rewritten message");
        assertEquals(first, second, "identical trees must not create new commits");

        String third = exporter.export("demo",
                Map.of("a.txt", "changed".getBytes()), "content update");
        assertTrue(!third.equals(first), "changed content must move the commit");
    }

    // ---- public clone URLs (audit 3.4: file:// → http for remote hosts) ----

    @Test
    void defaultUrlsStayFileScheme() throws Exception {
        LocalGitExporter exporter = new LocalGitExporter(tmp.toString(), "");
        exporter.export("skill-abc123",
                Map.of(".claude-plugin/plugin.json", "{}".getBytes()), "initial");
        String url = exporter.repoUrl("skill-abc123");
        assertTrue(url.startsWith("file://"), "default keeps file:// behavior: " + url);
        // Path.toUri() renders directories with a trailing slash.
        assertTrue(url.endsWith("skill-abc123.git/"),
                "repo dir name is part of the URL: " + url);
        assertTrue(Files.isDirectory(Path.of(java.net.URI.create(url))),
                "the file:// URL points at the on-disk repository");
    }

    @Test
    void publicBaseRewritesCloneUrlsToHttp() throws Exception {
        LocalGitExporter exporter = new LocalGitExporter(tmp.toString(),
                "https://store.example.com/git/");
        assertEquals("https://store.example.com/git/skill-abc123.git",
                exporter.repoUrl("skill-abc123"),
                "trailing slashes are trimmed; the .git dir name is appended");

        // The HTTP name matches the directory the GitServlet resolver serves.
        exporter.export("skill-abc123",
                Map.of(".claude-plugin/plugin.json", "{}".getBytes()), "test commit");
        assertTrue(Files.isDirectory(tmp.resolve("skill-abc123.git")),
                "the on-disk repo keeps the same sanitized name");
    }

    @Test
    void repoKeysAreSanitizedInBothSchemes() {
        LocalGitExporter exporter = new LocalGitExporter(tmp.toString(),
                "https://store.example.com/git");
        assertEquals("https://store.example.com/git/skill_bad_key.git",
                exporter.repoUrl("skill/bad key"));
        LocalGitExporter fileExporter = new LocalGitExporter(tmp.toString(), null);
        assertTrue(fileExporter.repoUrl("skill/bad key").endsWith("skill_bad_key.git"));
    }
}
