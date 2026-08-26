package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.UserSessionEntity;
import dev.infinia.store.infrastructure.persistence.repository.UserSessionJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SessionAdapter implements IdentityRepositories.SessionRepository {

    private final UserSessionJpaRepository jpa;

    public SessionAdapter(UserSessionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(IdentityRepositories.UserSessionRecord session) {
        UserSessionEntity e = jpa.findById(session.id()).orElseGet(UserSessionEntity::new);
        e.id = session.id();
        e.userId = session.userId();
        e.clientId = session.clientId();
        e.kind = session.kind();
        e.deviceId = session.deviceId();
        e.createdAt = session.createdAt();
        e.lastUsedAt = session.lastUsedAt();
        e.revoked = session.revoked();
        e.remoteIpHash = session.remoteIpHash();
        jpa.save(e);
    }

    @Override
    public List<IdentityRepositories.UserSessionRecord> findByUserId(UUID userId) {
        return jpa.findByUserIdAndRevokedFalse(userId).stream().map(SessionAdapter::toDomain).toList();
    }

    @Override
    public Optional<IdentityRepositories.UserSessionRecord> findById(UUID id) {
        return jpa.findById(id).map(SessionAdapter::toDomain);
    }

    @Override
    @Transactional
    public void markRevoked(UUID id) {
        jpa.markRevoked(id);
    }

    private static IdentityRepositories.UserSessionRecord toDomain(UserSessionEntity s) {
        return new IdentityRepositories.UserSessionRecord(s.id, s.userId, s.clientId, s.kind,
                s.deviceId, s.createdAt, s.lastUsedAt, s.revoked, s.remoteIpHash);
    }
}
