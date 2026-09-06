package dev.infinia.store.infrastructure.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Artifact-storage backend selection (design §5.1 artifact plane): the local
 * filesystem default, or one S3-compatible object store (MinIO, AWS S3, any
 * SigV4 endpoint) holding the same content-addressed blobs behind the
 * {@code BlobStorage} port. The backend never changes blob-key shape, so the
 * database stays valid across a later switch — blobs simply live where
 * {@code store.storage.type} points.
 *
 * <p>Local storage keeps its own {@code store.blob-dir} property; S3 settings
 * live under {@code store.storage.s3.*}.</p>
 */
@ConfigurationProperties(prefix = "store.storage")
public record BlobStorageProperties(Type type, S3 s3) {

    public enum Type { LOCAL, S3 }

    public BlobStorageProperties {
        if (type == null) {
            type = Type.LOCAL;
        }
        s3 = s3 == null ? new S3(null, null, null, null, null, null, null) : s3;
        if (type == Type.S3) {
            if (isBlank(s3.bucket)) {
                throw new IllegalStateException("store.storage.s3.bucket is required "
                        + "when store.storage.type=s3");
            }
            if (isBlank(s3.endpoint) && isBlank(s3.region)) {
                throw new IllegalStateException("store.storage.s3 requires either an "
                        + "endpoint (MinIO / S3-compatible) or a region (AWS S3)");
            }
            // Half-supplied credentials silently fall back to the ambient provider
            // chain and surface as a confusing 403 at first upload — refuse instead.
            if (isBlank(s3.accessKey) != isBlank(s3.secretKey)) {
                throw new IllegalStateException("store.storage.s3.access-key and "
                        + "store.storage.s3.secret-key must be supplied together");
            }
        }
    }

    public boolean s3Mode() {
        return type == Type.S3;
    }

    /**
     * S3-compatible endpoint settings. {@code pathStyleAccess} is nullable on
     * purpose: null means auto — path-style for custom endpoints (MinIO cannot
     * do virtual-host style without wildcard DNS), virtual-hosted for AWS S3.
     * Blank {@code accessKey}/{@code secretKey} fall back to the SDK's default
     * provider chain (env vars, profile, instance role).
     */
    public record S3(String endpoint, String region, String bucket, String accessKey,
            String secretKey, Boolean pathStyleAccess, String keyPrefix) {

        public S3 {
            keyPrefix = normalizeKeyPrefix(keyPrefix);
        }

        /**
         * Effective addressing style: an explicit setting wins; otherwise custom
         * endpoints (MinIO and friends) default to path-style because they cannot
         * do virtual-host style without wildcard DNS, and AWS S3 defaults to it.
         */
        public boolean effectivePathStyleAccess() {
            if (pathStyleAccess != null) {
                return pathStyleAccess;
            }
            return !isBlank(endpoint);
        }

        public String effectiveRegion() {
            return isBlank(region) ? "us-east-1" : region.trim();
        }

        /** Empty or {@code null} prefix becomes ""; otherwise a trailing {@code /} is ensured. */
        static String normalizeKeyPrefix(String prefix) {
            if (prefix == null || prefix.isBlank()) {
                return "";
            }
            String trimmed = prefix.trim();
            return trimmed.endsWith("/") ? trimmed : trimmed + "/";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
