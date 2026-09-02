package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.RemoteDatabase;
import dev.infinia.store.domain.port.SystemRepositories;
import dev.infinia.store.infrastructure.persistence.entity.RemoteDatabaseEntity;
import dev.infinia.store.infrastructure.persistence.repository.RemoteDatabaseJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RemoteDatabaseAdapter implements SystemRepositories.RemoteDatabaseRepository {

    private final RemoteDatabaseJpaRepository jpa;

    public RemoteDatabaseAdapter(RemoteDatabaseJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void save(RemoteDatabase database) {
        RemoteDatabaseEntity e = jpa.findById(database.id())
                .orElseGet(RemoteDatabaseEntity::new);
        e.id = database.id();
        e.name = database.name();
        e.jdbcUrl = database.jdbcUrl();
        e.username = database.username();
        e.passwordCipher = database.passwordCipher();
        e.enabled = database.enabled();
        e.lastTestedAt = database.lastTestedAt();
        e.lastTestOk = database.lastTestOk();
        e.lastTestError = database.lastTestError();
        e.createdAt = database.createdAt();
        e.updatedAt = database.updatedAt();
        jpa.save(e);
    }

    @Override
    public Optional<RemoteDatabase> findById(UUID id) {
        return jpa.findById(id).map(RemoteDatabaseAdapter::toDomain);
    }

    @Override
    public List<RemoteDatabase> findAll() {
        return jpa.findAllByOrderByNameAsc().stream()
                .map(RemoteDatabaseAdapter::toDomain).toList();
    }

    @Override
    public Optional<RemoteDatabase> findByName(String name) {
        return jpa.findByName(name).map(RemoteDatabaseAdapter::toDomain);
    }

    @Override
    public Optional<RemoteDatabase> findEnabled() {
        return jpa.findByEnabledTrue().map(RemoteDatabaseAdapter::toDomain);
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        jpa.deleteById(id);
    }

    private static RemoteDatabase toDomain(RemoteDatabaseEntity e) {
        return new RemoteDatabase(e.id, e.name, e.jdbcUrl, e.username, e.passwordCipher,
                e.enabled != null && e.enabled, e.lastTestedAt, e.lastTestOk, e.lastTestError,
                e.createdAt, e.updatedAt);
    }
}
