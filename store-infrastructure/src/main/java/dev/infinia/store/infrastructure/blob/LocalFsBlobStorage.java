package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Local filesystem, content-addressed blob store for development and tests
 * (design §5.1: production points {@code store.storage.type} at an S3-compatible
 * bucket behind the same port). Keys are {@code sha256/<first2>/<rest>};
 * completed blobs are immutable.
 */
public class LocalFsBlobStorage implements BlobStorage {

    private final Path root;

    public LocalFsBlobStorage(String dir) {
        // Same key StoreProperties used to validate — a mismatch here once silently
        // split blob storage across working directories (db anchored, blobs relative).
        if (!Path.of(dir).isAbsolute()) {
            throw new IllegalStateException("store.blob-dir must be absolute, got: " + dir);
        }
        this.root = Path.of(dir);
    }

    @Override
    public String put(InputStream in, long maxSizeBytes, String expectedSha256) {
        Path tmp = null;
        try {
            Files.createDirectories(root.resolve("tmp"));
            tmp = Files.createTempFile(root.resolve("tmp"), "upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > maxSizeBytes) {
                        throw new BlobStorageException("Upload exceeds the maximum allowed size of "
                                + maxSizeBytes + " bytes");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !sha256.equalsIgnoreCase(expectedSha256.trim())) {
                throw new BlobStorageException("SHA-256 mismatch: expected "
                        + expectedSha256 + " but computed " + sha256);
            }
            String blobKey = "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2);
            Path target = root.resolve(blobKey);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                // Content-addressed: identical content already stored.
                Files.delete(tmp);
            } else {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileAlreadyExistsException concurrentDuplicate) {
                    // Two uploads of identical content raced between the exists()
                    // check and the atomic move — the key is content-addressed, so
                    // the winner's blob is byte-identical: this upload succeeded too.
                    Files.delete(tmp);
                }
            }
            return blobKey;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            // Rejected (oversize / hash mismatch) or failed uploads must not leave
            // .part files behind — the triggers are client-controlled, so leaked
            // parts would silently eat the disk.
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort — the original failure is the one to report
                }
            }
            if (e instanceof BlobStorageException rejection) {
                throw rejection;
            }
            throw new BlobStorageException("Failed to store blob", e);
        }
    }

    @Override
    public InputStream open(String blobKey) {
        try {
            return Files.newInputStream(root.resolve(blobKey));
        } catch (IOException e) {
            throw new BlobStorageException("Blob not found: " + blobKey, e);
        }
    }

    @Override
    public boolean exists(String blobKey) {
        return Files.exists(root.resolve(blobKey));
    }

    @Override
    public long size(String blobKey) {
        try {
            return Files.size(root.resolve(blobKey));
        } catch (IOException e) {
            throw new BlobStorageException("Blob not found: " + blobKey, e);
        }
    }

    @Override
    public void checkWritable() {
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, "status-probe", ".tmp");
            Files.delete(probe);
        } catch (IOException e) {
            throw new BlobStorageException("Blob directory is not writable: " + root, e);
        }
    }
}
