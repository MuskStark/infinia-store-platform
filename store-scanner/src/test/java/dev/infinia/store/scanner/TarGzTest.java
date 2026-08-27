package dev.infinia.store.scanner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TarGzTest {

    @Test
    void roundTripsFilesAndStripsTopLevelDirectory() throws Exception {
        Map<String, byte[]> repo = new LinkedHashMap<>();
        repo.put("repo-HEAD/skills/example/SKILL.md", "# skill".getBytes());
        repo.put("repo-HEAD/skills/example/scripts/run.py", "print(1)".getBytes());
        repo.put("repo-HEAD/README.md", "readme".getBytes());

        byte[] tar = TarGz.tar(repo);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream compressor = new GZIPOutputStream(gz)) {
            compressor.write(tar);
        }

        Map<String, byte[]> extracted = TarGz.extract(
                new ByteArrayInputStream(gz.toByteArray()), 1024 * 1024);
        assertEquals(repo.keySet(), extracted.keySet());
        assertArrayEquals("print(1)".getBytes(), extracted.get(
                "repo-HEAD/skills/example/scripts/run.py"));

        Map<String, byte[]> stripped = TarGz.stripTopLevelDir(extracted);
        assertArrayEquals("# skill".getBytes(), stripped.get("skills/example/SKILL.md"));
        assertArrayEquals("readme".getBytes(), stripped.get("README.md"));
    }

    @Test
    void readsRealGzipStreams() throws Exception {
        Map<String, byte[]> files = Map.of("a/SKILL.md", "body".getBytes());
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream compressor = new GZIPOutputStream(gz)) {
            compressor.write(TarGz.tar(files));
        }
        // The sync service hands extract() the raw gz bytes; extract() owns the gunzip.
        Map<String, byte[]> extracted = TarGz.extract(
                new ByteArrayInputStream(gz.toByteArray()), 1024 * 1024);
        assertArrayEquals("body".getBytes(), extracted.get("a/SKILL.md"));
    }
}
