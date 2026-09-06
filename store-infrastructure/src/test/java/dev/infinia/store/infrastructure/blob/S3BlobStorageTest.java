package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage.BlobStorageException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3-compatible storage against a mocked client: the store's own contract —
 * streaming put with cap + digest, staging→promote flow, error wrapping — is
 * what's under test; wire behavior against a real MinIO is covered by the
 * compose stack. All requests are captured to pin the exact call shapes.
 */
class S3BlobStorageTest {

    private static final String BUCKET = "store-blobs";
    private static final int PART_SIZE = 8 * 1024 * 1024;

    private final S3Client s3 = mock(S3Client.class);
    private final S3BlobStorage storage = new S3BlobStorage(s3, BUCKET, null);

    @Test
    void smallBlobTakesTheSinglePutPath() {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String blobKey = storage.put(new ByteArrayInputStream(content), 1_000_000, null);

        assertThat(blobKey).isEqualTo(expectedKey(content));
        var request = capturePut();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(blobKey);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void emptyBlobIsASinglePut() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String blobKey = storage.put(new ByteArrayInputStream(new byte[0]), 1_000, null);

        assertThat(blobKey).isEqualTo(expectedKey(new byte[0]));
        assertThat(capturePut().key()).isEqualTo(blobKey);
    }

    @Test
    void oversizedBlobStagesThenPromotesWithServerSideCopy() {
        byte[] content = blobLargerThanOnePart();
        stubHappyMultipart();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        String blobKey = storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE, null);

        assertThat(blobKey).isEqualTo(expectedKey(content));
        verify(s3, times(2)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));

        var create = captor(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(create.capture());
        String stagingKey = create.getValue().key();
        assertThat(stagingKey).startsWith("staging/");

        var copy = captor(CopyObjectRequest.class);
        verify(s3).copyObject(copy.capture());
        assertThat(copy.getValue().sourceBucket()).isEqualTo(BUCKET);
        assertThat(copy.getValue().sourceKey()).isEqualTo(stagingKey);
        assertThat(copy.getValue().destinationKey()).isEqualTo(blobKey);

        var deleted = captor(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(stagingKey);
    }

    @Test
    void alreadyPublishedBlobSkipsTheCopy() {
        byte[] content = blobLargerThanOnePart();
        stubHappyMultipart();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).build());

        String blobKey = storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE, null);

        assertThat(blobKey).isEqualTo(expectedKey(content));
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
        verify(s3).deleteObject(any(DeleteObjectRequest.class)); // staging cleanup
    }

    @Test
    void promoteFailureAfterCompletionDeletesTheStagingObject() {
        byte[] content = blobLargerThanOnePart();
        stubHappyMultipart();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("copy failed").build());

        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE,
                null))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("Failed to publish blob");

        // The upload already completed, so abort is useless — the staging OBJECT
        // must be deleted or it lingers forever (lifecycle rules only reap
        // incomplete multipart uploads).
        var created = captor(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(created.capture());
        var deleted = captor(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(created.getValue().key());
        verify(s3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void hashMismatchOnSmallBlobNeverReachesS3() {
        assertThatThrownBy(() -> storage.put(
                new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                1_000_000, "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("SHA-256 mismatch");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void hashMismatchOnMultipartAbortsAndLeavesNothing() {
        byte[] content = blobLargerThanOnePart();
        stubHappyMultipart();

        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE,
                "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("SHA-256 mismatch");

        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
        var aborted = captor(AbortMultipartUploadRequest.class);
        verify(s3).abortMultipartUpload(aborted.capture());
        assertThat(aborted.getValue().key()).startsWith("staging/");
        assertThat(aborted.getValue().uploadId()).isEqualTo("upload-1");
    }

    @Test
    void sizeCapAbortsTheMultipartUpload() {
        byte[] content = blobLargerThanOnePart();
        stubHappyMultipart();
        long cap = content.length - 1L;

        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content), cap, null))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("maximum allowed size");

        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void sizeCapRejectsSmallBlobsBeforeAnyCall() {
        assertThatThrownBy(() -> storage.put(
                new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                4, null))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("maximum allowed size");
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void existsMapsHeadResults() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(7L).build())
                .thenThrow(NoSuchKeyException.builder().message("missing").build())
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThat(storage.exists("sha256/ab/cd")).isTrue();
        assertThat(storage.exists("sha256/ab/cd")).isFalse();
        assertThatThrownBy(() -> storage.exists("sha256/ab/cd"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("S3 error 500");
    }

    @Test
    void sizeReadsTheObjectLength() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(12345L).build());
        assertThat(storage.size("sha256/ab/cd")).isEqualTo(12345L);

        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertThatThrownBy(() -> storage.size("sha256/ab/cd"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("Blob not found");
    }

    @Test
    void openStreamsTheObject() throws IOException {
        byte[] content = "artifact bytes".getBytes(StandardCharsets.UTF_8);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                new ByteArrayInputStream(content)));

        try (var in = storage.open("sha256/ab/cd")) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        var requested = captor(GetObjectRequest.class);
        verify(s3).getObject(requested.capture());
        assertThat(requested.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requested.getValue().key()).isEqualTo("sha256/ab/cd");

        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertThatThrownBy(() -> storage.open("sha256/ab/cd"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("Blob not found");
    }

    @Test
    void writableCheckPutsAndDeletesAProbeObject() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        storage.checkWritable();

        var put = capturePut();
        assertThat(put.key()).startsWith("staging/status-probe-");
        var deleted = captor(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(put.key());
    }

    @Test
    void writableCheckWrapsCredentialFailures() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        assertThatThrownBy(storage::checkWritable)
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("not writable")
                .hasMessageContaining("S3 error 403");
    }

    @Test
    void keyPrefixNamespacesObjectsWithoutLeakingIntoBlobKeys() {
        S3BlobStorage prefixed = new S3BlobStorage(s3, BUCKET, "tenant1");
        byte[] content = "prefixed".getBytes(StandardCharsets.UTF_8);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).build());

        String blobKey = prefixed.put(new ByteArrayInputStream(content), 1_000, null);

        assertThat(blobKey).isEqualTo(expectedKey(content)); // logical key, unprefixed
        assertThat(capturePut().key()).isEqualTo("tenant1/" + blobKey);
        prefixed.exists(blobKey);
        var head = captor(HeadObjectRequest.class);
        verify(s3).headObject(head.capture());
        assertThat(head.getValue().key()).isEqualTo("tenant1/" + blobKey);
    }

    // ---- helpers ----

    private PutObjectRequest capturePut() {
        var captor = captor(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        return captor.getValue();
    }

    private <T> org.mockito.ArgumentCaptor<T> captor(Class<T> type) {
        return org.mockito.ArgumentCaptor.forClass(type);
    }

    private void stubHappyMultipart() {
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag").build());
    }

    private static byte[] blobLargerThanOnePart() {
        byte[] content = new byte[PART_SIZE + 100];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        return content;
    }

    private static String expectedKey(byte[] content) {
        return "sha256/" + sha256Hex(content).substring(0, 2)
                + "/" + sha256Hex(content).substring(2);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
