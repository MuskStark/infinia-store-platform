package dev.infinia.store.scanner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safe zip extraction with zip-slip and zip-bomb protection (design §13.1).
 *
 * <p>Content is read entirely into memory and never written to disk, which
 * neutralizes symlink escapes on the server side; path rules are still enforced so
 * the validated inventory matches what clients must handle. Limits (design §8.2
 * step 3): max entries, max total uncompressed size, max single entry size and a
 * max expansion ratio per entry.</p>
 */
public final class SafeZip {

    public record Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, int maxRatio) {
        public static Limits defaults() {
            return new Limits(5000, 200L * 1024 * 1024, 100L * 1024 * 1024, 200);
        }
    }

    /** A safely extracted text file: normalized relative path and UTF-8 content. */
    public record ExtractedFile(String path, byte[] content) {

        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private SafeZip() {}

    /**
     * Streams a zip, enforcing all limits. Only entries whose paths pass validation
     * are returned; directories are skipped.
     *
     * @throws ScanViolation when a limit or path rule is violated
     */
    public static Map<String, ExtractedFile> extract(InputStream zip, Limits limits)
            throws IOException {
        Map<String, ExtractedFile> files = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxEntries()) {
                    throw new ScanViolation("zip.too-many-entries",
                            "Archive exceeds the maximum of " + limits.maxEntries() + " entries");
                }
                String name = entry.getName();
                validatePath(name);
                if (entry.isDirectory()) {
                    continue;
                }
                long declared = entry.getSize(); // -1 when unknown
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long written = 0;
                int read;
                while ((read = zin.read(buffer)) > 0) {
                    written += read;
                    if (written > limits.maxEntryBytes()) {
                        throw new ScanViolation("zip.entry-too-large",
                                "Entry " + name + " exceeds the single-entry size limit");
                    }
                    if (written > limits.maxTotalBytes() || total + written > limits.maxTotalBytes()) {
                        throw new ScanViolation("zip.total-too-large",
                                "Archive exceeds the total uncompressed size limit");
                    }
                    if (declared > 0 && written / Math.max(declared, 1) > limits.maxRatio()) {
                        throw new ScanViolation("zip.bomb",
                                "Entry " + name + " exceeds the compression ratio limit");
                    }
                    out.write(buffer, 0, read);
                }
                total += written;
                files.put(name, new ExtractedFile(name, out.toByteArray()));
            }
        }
        return files;
    }

    public static void validatePath(String name) {
        if (name == null || name.isBlank()) {
            throw new ScanViolation("zip.invalid-path", "Empty entry name");
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new ScanViolation("zip.invalid-path", "Absolute entry path is not allowed: " + name);
        }
        for (String part : name.split("[/\\\\]")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new ScanViolation("zip.zip-slip",
                        "Path traversal is not allowed: " + name);
            }
            if (part.chars().anyMatch(c -> c == 0)) {
                throw new ScanViolation("zip.invalid-path", "NUL byte in entry path: " + name);
            }
        }
    }
}
