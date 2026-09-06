package dev.infinia.store.infrastructure.blob;

import dev.infinia.store.domain.port.BlobStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Wires exactly one {@link dev.infinia.store.domain.port.BlobStorage} backend
 * (design §5.1 artifact plane): the local filesystem default, or — with
 * {@code store.storage.type=s3} — one S3-compatible bucket (MinIO, AWS S3, any
 * SigV4 endpoint). Both keep the same content-addressed key shape, so the
 * choice is a deployment concern, never a data-model one.
 */
@Configuration
@EnableConfigurationProperties(BlobStorageProperties.class)
public class BlobStorageConfig {

    private static final String TYPE_PROPERTY = "store.storage.type";

    @Bean
    @ConditionalOnProperty(name = TYPE_PROPERTY, havingValue = "local", matchIfMissing = true)
    BlobStorage localFsBlobStorage(
            @Value("${store.blob-dir:${user.home}/.infinia-store/blobs}") String blobDir) {
        return new LocalFsBlobStorage(blobDir);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = TYPE_PROPERTY, havingValue = "s3")
    BlobStorage s3BlobStorage(BlobStorageProperties properties) {
        BlobStorageProperties.S3 s3 = properties.s3();
        S3Client client = S3Client.builder()
                .endpointOverride(s3.endpoint() == null || s3.endpoint().isBlank()
                        ? null : URI.create(s3.endpoint().trim()))
                .region(Region.of(s3.effectiveRegion()))
                .credentialsProvider(s3.accessKey() == null || s3.accessKey().isBlank()
                        || s3.secretKey() == null || s3.secretKey().isBlank()
                        ? DefaultCredentialsProvider.create()
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                s3.accessKey().trim(), s3.secretKey().trim())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.effectivePathStyleAccess())
                        .build())
                .build();
        return new S3BlobStorage(client, s3.bucket(), s3.keyPrefix());
    }
}
