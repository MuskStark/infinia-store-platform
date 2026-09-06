package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage.BlobStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local filesystem blob store: content addressing, the rejected-upload contract
 * and — the part a disk-filling attacker cares about — that refused uploads
 * never leave .part temp files behind.
 */
class LocalFsBlobStorageTest {

    @TempDir
    Path tempDir;

    private LocalFsBlobStorage storage() {
        return new LocalFsBlobStorage(tempDir.resolve("blobs").toString());
    }

    @Test
    void putReturnsTheContentAddressedKey() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String blobKey = storage().put(new ByteArrayInputStream(content), 1_000, null);

        assertThat(blobKey).isEqualTo("sha256/" + sha256Hex(content).substring(0, 2)
                + "/" + sha256Hex(content).substring(2));
        assertThat(Files.exists(tempDir.resolve("blobs").resolve(blobKey))).isTrue();
        assertThat(storage().size(blobKey)).isEqualTo(content.length);
    }

    @Test
    void oversizeUploadIsRejectedAndLeavesNoTempFile() {
        assertThatThrownBy(() -> storage().put(
                new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                4, null))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("maximum allowed size");
        assertThat(tempParts()).isEmpty();
    }

    @Test
    void hashMismatchIsRejectedAndLeavesNoTempFile() {
        assertThatThrownBy(() -> storage().put(
                new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                1_000, "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("SHA-256 mismatch");
        assertThat(tempParts()).isEmpty();
        // And nothing landed in the content-addressed tree either.
        assertThat(Files.notExists(tempDir.resolve("blobs/sha256"))).isTrue();
    }

    @Test
    void rejectedHashOnMatchingContentStillStoresWhenExpectedMatches() {
        byte[] content = "verify me".getBytes(StandardCharsets.UTF_8);
        String blobKey = storage().put(new ByteArrayInputStream(content), 1_000,
                sha256Hex(content));
        assertThat(blobKey).contains("sha256/");
    }

    @Test
    void checkWritableCreatesAndRemovesAProbeFile() {
        storage().checkWritable();
        assertThat(tempParts()).isEmpty();
    }

    @Test
    void relativeDirectoryIsRefused() {
        assertThatThrownBy(() -> new LocalFsBlobStorage("data/blobs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be absolute");
    }

    private java.util.List<String> tempParts() {
        try {
            return Files.list(tempDir.resolve("blobs/tmp"))
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (java.io.IOException e) {
            return java.util.List.of();
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
