package dev.infinia.store.domain.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Stable rollout bucketing (design §8.4): the bucket is derived from
 * {@code HMAC-SHA256(secret, installId)} — never from account, email or IP — so the
 * same opaque install id always lands in the same cohort.
 */
public final class RolloutBucketer {

    private final byte[] secret;

    public RolloutBucketer(String secretHexOrText) {
        this.secret = secretHexOrText.getBytes(StandardCharsets.UTF_8);
    }

    /** Bucket in [0, 100). */
    public int bucket(String installId) {
        if (installId == null || installId.isBlank()) {
            return 0;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(installId.getBytes(StandardCharsets.UTF_8));
            // Take the first 4 bytes as an unsigned 32-bit integer.
            long value = ((digest[0] & 0xFFL) << 24) | ((digest[1] & 0xFFL) << 16)
                    | ((digest[2] & 0xFFL) << 8) | (digest[3] & 0xFFL);
            return (int) (value % 100);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public boolean included(String installId, int rolloutPercent) {
        return bucket(installId) < Math.max(0, Math.min(100, rolloutPercent));
    }
}
