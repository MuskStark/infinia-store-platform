package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.service.UuidV7;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Content-addressed blob storage on one S3-compatible bucket (design §5.1:
 * production artifact plane). Same contract and blob-key shape as
 * {@link LocalFsBlobStorage} — {@code sha256/<first2>/<rest>} — so the database
 * stays valid no matter which backend serves it.
 *
 * <p>The store computes the digest while streaming, so an object's key is only
 * known after the last byte. Uploads therefore land on a {@code staging/}
 * object first and are then promoted with a server-side copy (never downloaded
 * again); a hash mismatch aborts the multipart upload and leaves nothing
 * behind. Blobs that fit a single part skip staging entirely and are PUT
 * straight to their final key.</p>
 */
public class S3BlobStorage implements BlobStorage, AutoCloseable {

    /** Multipart part size; S3 requires at least 5 MiB per part. */
    private static final int PART_SIZE = 8 * 1024 * 1024;

    /** CopyObject moves at most 5 GiB in one call; larger blobs copy by parts. */
    private static final long COPY_OBJECT_LIMIT = 5L * 1024 * 1024 * 1024;

    /** Server-side copy part size; the 10,000-part ceiling caps blobs at 10 TiB. */
    private static final long COPY_PART_SIZE = 1024L * 1024 * 1024;

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    public S3BlobStorage(S3Client s3, String bucket, String keyPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.keyPrefix = BlobStorageProperties.S3.normalizeKeyPrefix(keyPrefix);
    }

    @Override
    public String put(InputStream in, long maxSizeBytes, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // The first part is read separately: a blob that fits one buffer
            // (most artifacts) takes the single-request PUT path.
            byte[] first = new byte[PART_SIZE];
            int firstLength = readFully(in, first);
            digest.update(first, 0, firstLength);
            if (firstLength < PART_SIZE && firstLength <= maxSizeBytes) {
                String sha256 = HexFormat.of().formatHex(digest.digest());
                verifyHash(expectedSha256, sha256);
                String blobKey = blobKey(sha256);
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(keyPrefix + blobKey)
                        .build(),
                        RequestBody.fromByteBuffer(ByteBuffer.wrap(first, 0, firstLength)));
                return blobKey;
            }
            return putMultipart(in, first, firstLength, digest, maxSizeBytes, expectedSha256);
        } catch (NoSuchAlgorithmException e) {
            throw new BlobStorageException("SHA-256 is not available", e);
        } catch (BlobStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw storageFailure("Failed to store blob", e);
        }
    }

    private String putMultipart(InputStream in, byte[] first, int firstLength,
            MessageDigest digest, long maxSizeBytes, String expectedSha256) {
        long total = enforceCap(firstLength, maxSizeBytes);
        String stagingKey = keyPrefix + "staging/" + UuidV7.generate();
        String uploadId = null;
        boolean completed = false;
        try {
            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(stagingKey).build()).uploadId();
            List<CompletedPart> parts = new ArrayList<>();
            parts.add(uploadPart(stagingKey, uploadId, 1, first, firstLength));
            byte[] buffer = new byte[PART_SIZE];
            int partNumber = 2;
            for (int read; (read = readFully(in, buffer)) > 0; ) {
                total = enforceCap(total + read, maxSizeBytes);
                digest.update(buffer, 0, read);
                parts.add(uploadPart(stagingKey, uploadId, partNumber++, buffer, read));
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            verifyHash(expectedSha256, sha256);
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(stagingKey).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
            completed = true;
            String blobKey = blobKey(sha256);
            promote(stagingKey, blobKey, total);
            return blobKey;
        } catch (RuntimeException e) {
            if (completed) {
                // Aborting a completed upload is a no-op; the staging OBJECT is what
                // an incomplete promote leaves behind. Best-effort delete.
                deleteQuietly(stagingKey);
            } else {
                abortQuietly(stagingKey, uploadId);
            }
            if (e instanceof BlobStorageException blobFailure) {
                throw blobFailure;
            }
            throw storageFailure("Failed to store blob", e);
        }
    }

    /**
     * Moves a completed staging object to its final content-addressed key with a
     * server-side copy — the bytes never travel through the store again. An
     * already-present final key means identical content was published before;
     * staging is simply dropped.
     */
    private void promote(String stagingKey, String blobKey, long total) {
        if (total <= COPY_OBJECT_LIMIT) {
            if (!exists(blobKey)) {
                copy(stagingKey, keyPrefix + blobKey);
            }
        } else {
            promoteByPartCopy(stagingKey, blobKey, total);
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(stagingKey).build());
    }

    private void promoteByPartCopy(String stagingKey, String blobKey, long total) {
        String copyUploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(keyPrefix + blobKey).build()).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            for (long offset = 0, partNumber = 1; offset < total;
                    offset += COPY_PART_SIZE, partNumber++) {
                long length = Math.min(COPY_PART_SIZE, total - offset);
                String etag = s3.uploadPartCopy(UploadPartCopyRequest.builder()
                                .sourceBucket(bucket).sourceKey(stagingKey)
                                .destinationBucket(bucket).destinationKey(keyPrefix + blobKey)
                                .uploadId(copyUploadId).partNumber((int) partNumber)
                                .copySourceRange("bytes=" + offset + "-" + (offset + length - 1))
                                .build())
                        .copyPartResult().eTag();
                parts.add(CompletedPart.builder().partNumber((int) partNumber).eTag(etag).build());
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(keyPrefix + blobKey).uploadId(copyUploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (RuntimeException e) {
            abortQuietly(keyPrefix + blobKey, copyUploadId);
            throw storageFailure("Failed to publish blob " + blobKey, e);
        }
    }

    private void copy(String sourceKey, String destinationKey) {
        try {
            s3.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(destinationKey).build());
        } catch (RuntimeException e) {
            throw storageFailure("Failed to publish blob", e);
        }
    }

    private CompletedPart uploadPart(String stagingKey, String uploadId, int partNumber,
            byte[] data, int length) {
        UploadPartResponse response = s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(stagingKey).uploadId(uploadId).partNumber(partNumber)
                .contentLength((long) length).build(),
                RequestBody.fromByteBuffer(ByteBuffer.wrap(data, 0, length)));
        return CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build();
    }

    @Override
    public InputStream open(String blobKey) {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket)
                    .key(keyPrefix + blobKey).build());
        } catch (NoSuchKeyException e) {
            throw new BlobStorageException("Blob not found: " + blobKey, e);
        } catch (RuntimeException e) {
            throw storageFailure("Failed to open blob " + blobKey, e);
        }
    }

    @Override
    public boolean exists(String blobKey) {
        try {
            head(blobKey);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // Some S3-compatible servers answer a missing key with a bare 404
            // instead of the NoSuchKey error code.
            if (e.statusCode() == 404) {
                return false;
            }
            throw storageFailure("Failed to check blob " + blobKey, e);
        } catch (RuntimeException e) {
            throw storageFailure("Failed to check blob " + blobKey, e);
        }
    }

    @Override
    public long size(String blobKey) {
        try {
            return head(blobKey).contentLength();
        } catch (NoSuchKeyException e) {
            throw new BlobStorageException("Blob not found: " + blobKey, e);
        } catch (RuntimeException e) {
            throw storageFailure("Failed to size blob " + blobKey, e);
        }
    }

    @Override
    public void checkWritable() {
        String probeKey = keyPrefix + "staging/status-probe-" + UuidV7.generate();
        // Per-request timeouts: this probe runs on the status page's request
        // path, so a black-holed endpoint must fail in seconds, not hang it.
        java.util.function.Consumer<AwsRequestOverrideConfiguration.Builder> timeouts =
                override -> override.apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2));
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(probeKey)
                            .overrideConfiguration(timeouts).build(),
                    RequestBody.fromString("probe"));
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(probeKey)
                    .overrideConfiguration(timeouts).build());
        } catch (RuntimeException e) {
            throw storageFailure("Artifact storage is not writable (bucket " + bucket + ")", e);
        }
    }

    @Override
    public void close() {
        s3.close();
    }

    private HeadObjectResponse head(String blobKey) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket)
                .key(keyPrefix + blobKey).build());
    }

    private static String blobKey(String sha256) {
        return "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2);
    }

    private static void verifyHash(String expectedSha256, String sha256) {
        if (expectedSha256 != null && !sha256.equalsIgnoreCase(expectedSha256.trim())) {
            throw new BlobStorageException("SHA-256 mismatch: expected "
                    + expectedSha256 + " but computed " + sha256);
        }
    }

    private static long enforceCap(long total, long maxSizeBytes) {
        if (total > maxSizeBytes) {
            throw new BlobStorageException("Upload exceeds the maximum allowed size of "
                    + maxSizeBytes + " bytes");
        }
        return total;
    }

    private void abortQuietly(String key, String uploadId) {
        if (uploadId == null) {
            return;
        }
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (RuntimeException ignored) {
            // Orphaned parts age out via the bucket's abort-incomplete-upload
            // lifecycle rule; the original failure is the one to report.
        }
    }

    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException ignored) {
            // Best effort — the original failure is the one to report.
        }
    }

    private BlobStorageException storageFailure(String message, RuntimeException e) {
        if (e instanceof S3Exception s3Failure) {
            String errorCode = s3Failure.awsErrorDetails() == null
                    ? s3Failure.getMessage() : s3Failure.awsErrorDetails().errorCode();
            return new BlobStorageException(message + ": S3 error "
                    + s3Failure.statusCode() + " (" + errorCode + ")", e);
        }
        return new BlobStorageException(message + ": " + e.getMessage(), e);
    }

    /** Reads until the buffer is full or the stream ends; returns bytes read. */
    private static int readFully(InputStream in, byte[] buffer) {
        try {
            int total = 0;
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total;
        } catch (IOException e) {
            throw new BlobStorageException("Failed to read the upload stream", e);
        }
    }
}
