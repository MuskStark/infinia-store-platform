package dev.infinia.store.scanner;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal tar.gz reader for upstream aggregation: extracts regular files
 * (following GNU long-name entries) from a gzipped tar stream. Sizes are
 * capped like the rest of the package pipeline.
 */
public final class TarGz {

    private TarGz() {}

    /** path (with the leading top-level directory kept) → content. */
    public static Map<String, byte[]> extract(InputStream tarGz, long maxTotalBytes)
            throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(tarGz)) {
            byte[] header = new byte[512];
            String pendingLongName = null;
            while (true) {
                int read = 0;
                while (read < 512) {
                    int n = gzip.read(header, read, 512 - read);
                    if (n < 0) {
                        throw new EOFException("truncated tar header");
                    }
                    read += n;
                }
                if (isZeroBlock(header)) {
                    break; // end of archive
                }
                String name = cString(header, 0, 100);
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                if (size > 256L * 1024 * 1024) {
                    throw new IOException("tar entry too large: " + name);
                }
                byte[] content = new byte[(int) size];
                int got = 0;
                while (got < size) {
                    int n = gzip.read(content, got, (int) size - got);
                    if (n < 0) {
                        throw new EOFException("truncated tar entry: " + name);
                    }
                    got += n;
                }
                skipPad(gzip, size);
                if (type == 'L') { // GNU long name applies to the next entry
                    pendingLongName = new String(content, StandardCharsets.UTF_8).trim();
                    continue;
                }
                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }
                if (type == '0' || type == '\0') {
                    total += size;
                    if (total > maxTotalBytes) {
                        throw new IOException("tar archive exceeds size budget");
                    }
                    files.put(name, content);
                }
            }
        }
        return files;
    }

    /** Strips the single top-level directory GitHub/Bitbucket archives add. */
    public static Map<String, byte[]> stripTopLevelDir(Map<String, byte[]> files) {
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            int slash = e.getKey().indexOf('/');
            if (slash < 0) {
                stripped.put(e.getKey(), e.getValue()); // no wrapping directory
            } else {
                stripped.put(e.getKey().substring(slash + 1), e.getValue());
            }
        }
        return stripped;
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        int pad = (int) ((512 - (size % 512)) % 512);
        for (int i = 0; i < pad; i++) {
            if (in.read() < 0) {
                throw new EOFException("truncated tar padding");
            }
        }
    }

    private static String cString(byte[] block, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] block, int offset, int length) {
        String text = cString(block, offset, length).trim();
        return text.isEmpty() ? 0 : Long.parseLong(text, 8);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** Helper used by tests to build tar archives without external libraries. */
    public static byte[] tar(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            System.arraycopy(name, 0, header, 0, Math.min(100, name.length));
            String sizeField = String.format("%011o", e.getValue().length) + " ";
            System.arraycopy(sizeField.getBytes(StandardCharsets.ISO_8859_1), 0, header, 124, 12);
            header[156] = '0';
            String checksumPlaceholder = "        ";
            System.arraycopy(checksumPlaceholder.getBytes(StandardCharsets.ISO_8859_1), 0,
                    header, 148, 8);
            header[257] = 'u';
            header[258] = 's';
            header[259] = 't';
            header[260] = 'a';
            header[261] = 'r';
            int checksum = 0;
            for (byte b : header) {
                checksum += b & 0xFF;
            }
            String checksumField = String.format("%06o", checksum) + "\0 ";
            System.arraycopy(checksumField.getBytes(StandardCharsets.ISO_8859_1), 0, header, 148,
                    8);
            out.writeBytes(header);
            out.writeBytes(e.getValue());
            int pad = (512 - (e.getValue().length % 512)) % 512;
            out.write(new byte[pad]);
        }
        out.write(new byte[1024]); // two zero blocks terminate the archive
        return out.toByteArray();
    }
}
