package dev.infinia.store.domain.port;

import dev.infinia.store.domain.model.RemoteDatabase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * System-administration ports: remote database endpoints configured by
 * platform admins (远程数据库配置).
 */
public final class SystemRepositories {

    private SystemRepositories() {}

    public interface RemoteDatabaseRepository {

        void save(RemoteDatabase database);

        Optional<RemoteDatabase> findById(UUID id);

        List<RemoteDatabase> findAll();

        Optional<RemoteDatabase> findByName(String name);

        /** The row currently activated as the data-source override, if any. */
        Optional<RemoteDatabase> findEnabled();

        void deleteById(UUID id);
    }
}
