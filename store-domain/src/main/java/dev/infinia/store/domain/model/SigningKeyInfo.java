package dev.infinia.store.domain.model;

import java.time.Instant;

/**
 * Public signing key metadata (design §8.3). Private keys live in KMS/HSM in
 * production and never enter the database.
 */
public record SigningKeyInfo(String keyId, String algorithm, String publicKeyBase64,
        String ownerType, String ownerRef, String status, Instant validFrom, Instant validTo) {

    public static final String OWNER_PLATFORM = "PLATFORM";
    public static final String OWNER_PUBLISHER = "PUBLISHER";

    public boolean activeAt(Instant now) {
        return "ACTIVE".equals(status)
                && (validFrom == null || !now.isBefore(validFrom))
                && (validTo == null || now.isBefore(validTo));
    }
}
