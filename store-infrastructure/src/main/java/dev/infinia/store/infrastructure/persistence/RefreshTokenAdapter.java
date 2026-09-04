package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.IdentityRepositories.RefreshTokenRecord;
import dev.infinia.store.infrastructure.persistence.entity.RefreshTokenEntity;
import dev.infinia.store.infrastructure.persistence.repository.RefreshTokenJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JPA adapter for the desktop refresh-credential ledger (design §7.2). */
@Component
public class RefreshTokenAdapter implements IdentityRepositories.RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenAdapter(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(RefreshTokenRecord token) {
        RefreshTokenEntity e = new RefreshTokenEntity();
        e.tokenHash = token.tokenHash();
        e.sessionId = token.sessionId();
        e.userId = token.userId();
        e.clientId = token.clientId();
        e.createdAt = token.createdAt();
        e.expiresAt = token.expiresAt();
        e.absoluteDeadline = token.absoluteDeadline();
        e.consumedAt = token.consumedAt();
        e.revoked = token.revoked();
        jpa.save(e);
    }

    @Override
    public Optional<RefreshTokenRecord> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(RefreshTokenAdapter::toDomain);
    }

    @Override
    public List<RefreshTokenRecord> findBySessionId(UUID sessionId) {
        return jpa.findBySessionId(sessionId).stream()
                .map(RefreshTokenAdapter::toDomain).toList();
    }

    @Override
    @Transactional
    public int consume(String tokenHash, Instant now) {
        return jpa.consume(tokenHash, now);
    }

    @Override
    @Transactional
    public int revokeFamily(UUID sessionId) {
        return jpa.revokeFamily(sessionId);
    }

    private static RefreshTokenRecord toDomain(RefreshTokenEntity e) {
        return new RefreshTokenRecord(e.tokenHash, e.sessionId, e.userId, e.clientId,
                e.createdAt, e.expiresAt, e.absoluteDeadline, e.consumedAt, e.revoked);
    }
}
