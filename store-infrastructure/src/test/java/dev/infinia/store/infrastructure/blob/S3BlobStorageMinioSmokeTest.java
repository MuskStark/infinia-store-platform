package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage.BlobStorageException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level smoke test against a live S3-compatible server (MinIO). Opt-in
 * via environment because the default suite must stay Docker/network-free:
 *
 * <pre>
 * TEST_S3_ENDPOINT=http://127.0.0.1:9100 \
 * TEST_S3_ACCESS_KEY=store TEST_S3_SECRET_KEY=store-secret \
 * TEST_S3_BUCKET=store-blobs-smoke \
 * ./mvnw -pl store-infrastructure test -Dtest=S3BlobStorageMinioSmokeTest
 * </pre>
 *
 * Exercises exactly what the mocked unit test cannot prove: SigV4 signing,
 * real multipart/chunked encoding, server-side copy and cleanup.
 */
@EnabledIfEnvironmentVariable(named = "TEST_S3_ENDPOINT", matches = ".+")
class S3BlobStorageMinioSmokeTest {

    private static final int PART_SIZE = 8 * 1024 * 1024;

    private static final S3Client client = S3Client.builder()
            .endpointOverride(java.net.URI.create(System.getenv("TEST_S3_ENDPOINT")))
            .region(Region.of(env("TEST_S3_REGION", "us-east-1")))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    System.getenv("TEST_S3_ACCESS_KEY"), System.getenv("TEST_S3_SECRET_KEY"))))
            .build();

    private static final String bucket = env("TEST_S3_BUCKET", "store-blobs-smoke");

    private static final S3BlobStorage storage =
            new S3BlobStorage(client, bucket, env("TEST_S3_KEY_PREFIX", null));

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void bucketExists() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException notFound) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Test
    void smallBlobRoundTripsThroughTheSinglePutPath() throws IOException {
        byte[] content = deterministicBytes(1024, 1);
        String blobKey = storage.put(new ByteArrayInputStream(content), 1_000_000, null);

        assertThat(blobKey).matches("sha256/[0-9a-f]{2}/[0-9a-f]{62}");
        assertThat(storage.exists(blobKey)).isTrue();
        assertThat(storage.size(blobKey)).isEqualTo(content.length);
        try (var in = storage.open(blobKey)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        storage.checkWritable(); // probe object comes and goes
    }

    @Test
    void largeBlobRoundTripsThroughMultipartStaging() throws IOException {
        byte[] content = deterministicBytes(PART_SIZE + 12345, 2);
        String blobKey = storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE, null);

        assertThat(storage.size(blobKey)).isEqualTo(content.length);
        assertThat(storage.open(blobKey).readAllBytes()).isEqualTo(content);
        assertThat(stagingObjects()).isZero();

        // Content addressing: re-uploading identical bytes lands on the same key.
        String again = storage.put(new ByteArrayInputStream(content), Long.MAX_VALUE, null);
        assertThat(again).isEqualTo(blobKey);
        assertThat(stagingObjects()).isZero();
    }

    @Test
    void verifiedHashIsEnforcedAgainstRealContent() {
        byte[] content = deterministicBytes(10, 3);
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content), 1_000_000,
                "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("SHA-256 mismatch");

        byte[] multipartContent = deterministicBytes(PART_SIZE + 10, 4);
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(multipartContent),
                Long.MAX_VALUE, "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(BlobStorageException.class);
        assertThat(stagingObjects()).isZero();
    }

    @Test
    void sizeCapStopsTheUploadAndLeavesNoObjects() {
        byte[] content = deterministicBytes(PART_SIZE + 100, 5);
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(content),
                PART_SIZE + 50, null))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("maximum allowed size");
        assertThat(stagingObjects()).isZero();
    }

    private static int stagingObjects() {
        return client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                        .prefix((env("TEST_S3_KEY_PREFIX", "") + "staging/")).build())
                .contents().size();
    }

    private static byte[] deterministicBytes(int size, int seed) {
        byte[] content = new byte[size];
        new Random(seed).nextBytes(content);
        return content;
    }
}
