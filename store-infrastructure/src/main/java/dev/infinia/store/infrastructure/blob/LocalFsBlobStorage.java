package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * (design §5.1: production uses S3/MinIO behind the same port). Keys are
 * {@code sha256/<first2>/<rest>}; completed blobs are immutable.
 */
@Component
public class LocalFsBlobStorage implements BlobStorage {

    private final Path root;

    public LocalFsBlobStorage(@Value("${store.blob.local-dir:data/blobs}") String dir) {
        this.root = Path.of(dir);
    }

    @Override
    public String put(InputStream in, long maxSizeBytes, String expectedSha256) {
        Path tmp;
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
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(expectedSha256.trim())) {
                throw new BlobStorageException("SHA-256 mismatch: expected "
                        + expectedSha256 + " but computed " + sha256);
            }
            if (expectedSha256 == null && !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new BlobStorageException("Computed hash is not a valid SHA-256");
            }
            String blobKey = "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2);
            Path target = root.resolve(blobKey);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                // Content-addressed: identical content already stored.
                Files.delete(tmp);
            } else {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return blobKey;
        } catch (IOException | NoSuchAlgorithmException e) {
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
}
