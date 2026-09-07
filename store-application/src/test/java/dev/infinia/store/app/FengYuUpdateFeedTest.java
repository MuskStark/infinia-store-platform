package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debian generic update feed (audit 3.3): the desktop client points
 * electron-updater's generic provider at {@code {base}/fengyu-updates/deb} with
 * channel {@code latest} — it fetches {@code latest-linux.yml} and the deb
 * artifacts from that same directory. The feed must mirror electron-builder's
 * naming ({@code Infinia-<version>-linux-<arch>.deb}) and carry sha512 as the
 * Base64 of the SHA-512 over the SERVED bytes, never a fabricated value.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FengYuUpdateFeedTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    void latestLinuxYmlMatchesElectronUpdaterContract() throws Exception {
        ResponseEntity<String> yml = http().get("/fengyu-updates/deb/latest-linux.yml", null);
        assertEquals(200, yml.getStatusCode().value(), "body: " + yml.getBody());
        String body = yml.getBody();

        assertEquals("4.1.0", valueOf(body, "version"),
                "the newest published stable deb release is announced");
        assertTrue(body.contains("releaseDate: '"),
                "releaseDate must be present (quoted ISO): " + body);

        // Seeded artifact naming mirrors electron-builder's artifactName
        // ${productName}-${version}-${platform}-${arch}.${ext} for the deb target.
        assertEquals("Infinia-4.1.0-linux-x64.deb", valueOf(body, "path"),
                "path is the primary (lite) deb");
        assertEquals("Infinia-4.1.0-linux-x64.deb", fileUrl(body, 0));
        assertTrue(sizeOf(body, 0) > 0, "size must be derived from the stored blob");
        String ymlSha512 = fileSha512(body, 0);
        assertEquals(88, ymlSha512.length(), "base64 sha512 of the 64-byte digest");
        assertEquals(ymlSha512, valueOf(body, "sha512"),
                "top-level sha512 mirrors the primary file");
    }

    @Test
    void debArtifactIsServedFromTheFeedDirectoryWithHonestDigest() throws Exception {
        ResponseEntity<byte[]> deb = http().getBytes("/fengyu-updates/deb/Infinia-4.1.0-linux-x64.deb");
        assertEquals(200, deb.getStatusCode().value());
        assertEquals("application/vnd.debian.binary-package",
                deb.getHeaders().getFirst("Content-Type"));
        assertEquals(String.valueOf(deb.getBody().length),
                deb.getHeaders().getFirst("Content-Length"));

        // sha512/size in the yml must be derived from exactly these bytes.
        ResponseEntity<String> yml = http().get("/fengyu-updates/deb/latest-linux.yml", null);
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        String expected = Base64.getEncoder().encodeToString(sha512.digest(deb.getBody()));
        assertEquals(expected, fileSha512(yml.getBody(), 0),
                "the yml digest must match the served bytes");
        assertEquals(deb.getBody().length, sizeOf(yml.getBody(), 0));
    }

    @Test
    void unknownArtifactsAndTraversalAttemptsAreRefused() {
        assertEquals(404, http().get("/fengyu-updates/deb/not-a-release.deb", null)
                .getStatusCode().value());
        ResponseEntity<String> traversal = http().get(
                "/fengyu-updates/deb/..%2F..%2Fetc%2Fpasswd", null);
        assertTrue(traversal.getStatusCode().value() >= 400,
                "path traversal must not resolve: " + traversal.getStatusCode());
    }

    // ---- tiny line-oriented yml accessors (the document shape is fixed) ----

    private static String valueOf(String yml, String key) {
        Matcher m = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE)
                .matcher(yml);
        assertTrue(m.find(), "missing key " + key + " in:\n" + yml);
        return m.group(1).trim();
    }

    private static String fileUrl(String yml, int index) {
        return fileEntry(yml, index, "url");
    }

    private static String fileSha512(String yml, int index) {
        return fileEntry(yml, index, "sha512");
    }

    private static int sizeOf(String yml, int index) {
        return Integer.parseInt(fileEntry(yml, index, "size"));
    }

    private static String fileEntry(String yml, int index, String field) {
        Matcher m = Pattern.compile("^\\s+- url: (.+)$\\n\\s+sha512: (\\S+)\\n\\s+size: (\\d+)$",
                Pattern.MULTILINE).matcher(yml);
        for (int i = 0; i <= index; i++) {
            assertTrue(m.find(), "missing files[" + index + "] in:\n" + yml);
        }
        return switch (field) {
            case "url" -> m.group(1).trim();
            case "sha512" -> m.group(2).trim();
            case "size" -> m.group(3).trim();
            default -> throw new IllegalArgumentException(field);
        };
    }
}
