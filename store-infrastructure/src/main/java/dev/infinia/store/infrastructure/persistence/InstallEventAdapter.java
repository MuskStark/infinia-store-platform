package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.InstallEventRecord;
import dev.infinia.store.domain.port.LibraryRepositories;
import dev.infinia.store.infrastructure.persistence.entity.InstallEventEntity;
import dev.infinia.store.infrastructure.persistence.repository.InstallEventJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class InstallEventAdapter implements LibraryRepositories.InstallEventRepository {

    private final InstallEventJpaRepository jpa;

    public InstallEventAdapter(InstallEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return jpa.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public void save(InstallEventRecord event) {
        InstallEventEntity e = new InstallEventEntity();
        e.id = event.id();
        e.idempotencyKey = event.idempotencyKey();
        e.userId = event.userId();
        e.deviceId = event.deviceId();
        e.coordinate = event.coordinate();
        e.version = event.version();
        e.type = event.type();
        e.action = event.action();
        e.outcome = event.outcome();
        e.hostVersion = event.hostVersion();
        e.os = event.os();
        e.arch = event.arch();
        e.occurredAt = event.occurredAt();
        e.receivedAt = event.receivedAt();
        jpa.save(e);
    }

    @Override
    public List<InstallEventRecord> findRecentByUserId(UUID userId, int limit) {
        return jpa.findTop100ByUserIdOrderByReceivedAtDesc(userId).stream()
                .limit(limit)
                .map(InstallEventAdapter::toDomain)
                .toList();
    }

    private static InstallEventRecord toDomain(InstallEventEntity e) {
        return new InstallEventRecord(e.id, e.idempotencyKey, e.userId, e.deviceId, e.coordinate,
                e.version, e.type, e.action, e.outcome, e.hostVersion, e.os, e.arch,
                e.occurredAt, e.receivedAt);
    }
}
