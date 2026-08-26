package dev.infinia.store.scanner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeZipTest {

    private static byte[] zip(EntryWriter... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (EntryWriter writer : entries) {
                writer.write(zos);
            }
        }
        return out.toByteArray();
    }

    interface EntryWriter {
        void write(ZipOutputStream zos) throws IOException;
    }

    private static EntryWriter file(String name, String content) {
        return zos -> {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        };
    }

    private static EntryWriter dir(String name) {
        return zos -> zos.putNextEntry(new ZipEntry(name.endsWith("/") ? name : name + "/"));
    }

    @Test
    void extractsValidZip() throws IOException {
        byte[] zip = zip(dir("assets/"), file("plugin.json", "{}"),
                file("assets/icon.svg", "<svg/>"));
        Map<String, SafeZip.ExtractedFile> files = SafeZip.extract(
                new ByteArrayInputStream(zip), SafeZip.Limits.defaults());
        assertEquals(2, files.size());
        assertTrue(files.containsKey("plugin.json"));
        assertEquals("<svg/>", files.get("assets/icon.svg").text());
    }

    @Test
    void rejectsZipSlip() throws IOException {
        byte[] zip = zip(file("../escape.txt", "nope"));
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(zip), SafeZip.Limits.defaults()));
        assertEquals("zip.zip-slip", violation.rule);
    }

    @Test
    void rejectsAbsolutePaths() throws IOException {
        byte[] unix = zip(file("/etc/passwd", "x"));
        assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(unix), SafeZip.Limits.defaults()));

        byte[] windows = zip(file("C:\\Windows\\evil.dll", "x"));
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(windows), SafeZip.Limits.defaults()));
        assertEquals("zip.invalid-path", violation.rule);
    }

    @Test
    void rejectsTooManyEntries() throws IOException {
        // 10-entry limit
        EntryWriter[] entries = new EntryWriter[12];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = file("f" + i + ".txt", "x");
        }
        byte[] zip = zip(entries);
        SafeZip.Limits limits = new SafeZip.Limits(10, 1_000_000, 500_000, 100);
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(zip), limits));
        assertEquals("zip.too-many-entries", violation.rule);
    }

    @Test
    void rejectsOversizedEntry() throws IOException {
        byte[] zip = zip(file("big.txt", "a".repeat(5000)));
        SafeZip.Limits limits = new SafeZip.Limits(10, 1_000_000, 1000, 100);
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(zip), limits));
        assertEquals("zip.entry-too-large", violation.rule);
    }

    @Test
    void rejectsTotalSizeOverflow() throws IOException {
        byte[] zip = zip(file("a.txt", "a".repeat(4000)), file("b.txt", "b".repeat(4000)));
        SafeZip.Limits limits = new SafeZip.Limits(10, 5000, 4000, 100);
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(zip), limits));
        assertEquals("zip.total-too-large", violation.rule);
    }

    @Test
    void rejectsNulBytePaths() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("bad\u0000name.txt");
            zos.putNextEntry(entry);
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ScanViolation violation = assertThrows(ScanViolation.class,
                () -> SafeZip.extract(new ByteArrayInputStream(out.toByteArray()),
                        SafeZip.Limits.defaults()));
        assertEquals("zip.invalid-path", violation.rule);
    }
}
