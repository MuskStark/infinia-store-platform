package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backend selection under {@code store.storage.type}: exactly one BlobStorage
 * bean, local by default, S3 when configured — and clear boot failures for
 * half-configured deployments instead of runtime surprises on first upload.
 */
class BlobStorageConfigTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BlobStorageConfig.class);

    @Test
    void localFilesystemIsTheDefaultBackend() {
        context.withPropertyValues("store.blob-dir=" + tempDir.resolve("blobs"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(BlobStorage.class);
                    assertThat(ctx.getBean(BlobStorage.class))
                            .isInstanceOf(LocalFsBlobStorage.class);
                });
    }

    @Test
    void explicitLocalTypeSelectsTheFilesystemBackend() {
        context.withPropertyValues("store.storage.type=local",
                        "store.blob-dir=" + tempDir.resolve("blobs"))
                .run(ctx -> assertThat(ctx.getBean(BlobStorage.class))
                        .isInstanceOf(LocalFsBlobStorage.class));
    }

    @Test
    void s3TypeSelectsTheObjectStorageBackend() {
        context.withPropertyValues(
                        "store.storage.type=s3",
                        "store.storage.s3.endpoint=http://localhost:9000",
                        "store.storage.s3.bucket=store-blobs",
                        "store.storage.s3.access-key=store",
                        "store.storage.s3.secret-key=store-secret")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(BlobStorage.class);
                    assertThat(ctx.getBean(BlobStorage.class)).isInstanceOf(S3BlobStorage.class);
                });
    }

    @Test
    void s3WithoutBucketFailsAtBoot() {
        context.withPropertyValues(
                        "store.storage.type=s3",
                        "store.storage.s3.endpoint=http://localhost:9000")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(failureMessages(ctx.getStartupFailure()))
                            .contains("store.storage.s3.bucket is required");
                });
    }

    @Test
    void s3WithoutEndpointOrRegionFailsAtBoot() {
        context.withPropertyValues(
                        "store.storage.type=s3",
                        "store.storage.s3.bucket=store-blobs")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(failureMessages(ctx.getStartupFailure()))
                            .contains("either an endpoint");
                });
    }

    /** Bean-creation failures arrive wrapped in binder/factory chains; the operator-facing message is nested. */
    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(String.valueOf(cause.getMessage())).append(" | ");
        }
        return messages.toString();
    }

    @Test
    void relativeBlobDirIsRefusedForLocalStorage() {
        context.withPropertyValues("store.blob-dir=data/blobs")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("store.blob-dir must be absolute");
                });
    }
}
