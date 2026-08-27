package dev.infinia.store.infrastructure.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGitExporterTest {

    @TempDir
    Path tmp;

    @Test
    void unchangedContentKeepsCommitSha() throws Exception {
        LocalGitExporter exporter = new LocalGitExporter(tmp.toString());
        String first = exporter.export("demo",
                Map.of("a.txt", "hello".getBytes()), "initial");
        String second = exporter.export("demo",
                Map.of("a.txt", "hello".getBytes()), "rewritten message");
        assertEquals(first, second, "identical trees must not create new commits");

        String third = exporter.export("demo",
                Map.of("a.txt", "changed".getBytes()), "content update");
        assertTrue(!third.equals(first), "changed content must move the commit");
    }
}
