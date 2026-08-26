package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Local credential: password hash, passkey or MFA metadata (design §7.1). */
public record Credential(UUID id, UUID userId, CredentialType type, String secretHash, Instant createdAt) {

    public enum CredentialType { PASSWORD, PASSKEY, TOTP }
}
