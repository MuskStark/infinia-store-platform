package dev.infinia.store.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A remote database endpoint registered by a platform admin (远程数据库配置):
 * JDBC coordinates plus an encrypted password. Exactly one enabled row is the
 * store's active data-source override (written to the override file and applied
 * on the next restart); the rest are standby entries.
 */
public record RemoteDatabase(
        UUID id,
        String name,
        String jdbcUrl,
        String username,
        String passwordCipher,
        boolean enabled,
        Instant lastTestedAt,
        Boolean lastTestOk,
        String lastTestError,
        Instant createdAt,
        Instant updatedAt) {
}
