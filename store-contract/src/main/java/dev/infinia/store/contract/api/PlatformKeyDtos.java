package dev.infinia.store.contract.api;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the public platform-key registry (design §8.3): the Ed25519 anchors
 * FengYu hosts provision into their {@code trusted-store-keys.json}. Public
 * keys are public — JWKS precedent — so the store itself is the bootstrap
 * channel; what it must never carry is the private half (KMS/HSM in
 * production, ADR-006).
 */
public final class PlatformKeyDtos {

    private PlatformKeyDtos() {}

    /**
     * One ACTIVE platform signing key. {@code publicKeyBase64} is the Base64
     * X.509 DER encoding the host trust registry expects verbatim.
     */
    public record PlatformKeyDto(
            String keyId,
            String algorithm,
            String publicKeyBase64,
            Instant validFrom,
            Instant validTo) {
    }

    /** Mirrors the host trust-registry document shape: {@code {"keys":[...]}}. */
    public record PlatformKeysDto(List<PlatformKeyDto> keys) {
    }
}
