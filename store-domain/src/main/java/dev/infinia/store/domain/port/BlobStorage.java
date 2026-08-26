package dev.infinia.store.domain.port;

import java.io.InputStream;

/**
 * Content-addressed blob storage port (design §5.1 artifact plane). Implementations:
 * local filesystem for development, S3/MinIO for production. Blob keys are derived
 * from the SHA-256; completed blobs are immutable.
 */
public interface BlobStorage {

    /**
     * Streams content into storage, enforcing the size cap, and returns the blob key.
     *
     * @throws BlobStorageException on size violation, hash mismatch or I/O failure
     */
    String put(InputStream in, long maxSizeBytes, String expectedSha256);

    InputStream open(String blobKey);

    boolean exists(String blobKey);

    long size(String blobKey);

    class BlobStorageException extends RuntimeException {
        public BlobStorageException(String message) {
            super(message);
        }

        public BlobStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
