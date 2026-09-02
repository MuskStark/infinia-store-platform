package dev.infinia.store.app.service;

import dev.infinia.store.app.config.RemoteDataSourceOverride;
import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.contract.api.AdminDatabaseDtos.CreateRemoteDatabaseRequest;
import dev.infinia.store.contract.api.AdminDatabaseDtos.DataSourceStatusDto;
import dev.infinia.store.contract.api.AdminDatabaseDtos.RemoteDatabaseDto;
import dev.infinia.store.contract.api.AdminDatabaseDtos.TestResultDto;
import dev.infinia.store.contract.api.AdminDatabaseDtos.UpdateRemoteDatabaseRequest;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.RemoteDatabase;
import dev.infinia.store.domain.port.SystemRepositories.RemoteDatabaseRepository;
import dev.infinia.store.domain.service.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Admin-managed remote database endpoints (远程数据库配置): registration with
 * AES-sealed credentials, live connectivity probes, and activation — writing
 * the {@link RemoteDataSourceOverride} file the startup post-processor applies
 * to {@code spring.datasource.*} on the next restart. Every mutation is audited.
 */
@Service
public class RemoteDatabaseService {

    private static final long TEST_TIMEOUT_SECONDS = 8;

    /** Probes run off the request thread so unreachable hosts cannot pile up. */
    private static final ExecutorService PROBES = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "remote-db-probe");
        thread.setDaemon(true);
        return thread;
    });

    private final RemoteDatabaseRepository databases;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final StoreProperties properties;
    private final DataSource dataSource;

    public RemoteDatabaseService(RemoteDatabaseRepository databases, SecretCipher cipher,
            AuditService audit, StoreProperties properties, DataSource dataSource) {
        this.databases = databases;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
        this.dataSource = dataSource;
    }

    // ---- registry ----

    public List<RemoteDatabaseDto> list() {
        return databases.findAll().stream().map(RemoteDatabaseService::toDto).toList();
    }

    public RemoteDatabaseDto getOrThrow(UUID id) {
        return toDto(require(id));
    }

    @Transactional
    public RemoteDatabaseDto create(UUID adminId, CreateRemoteDatabaseRequest request) {
        if (request == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "Request body required");
        }
        String name = requireText(request.name(), "name", 1, 100);
        String url = validatedUrl(request.jdbcUrl());
        String username = requireText(request.username(), "username", 1, 200);
        if (request.password() == null || request.password().isBlank()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "password is required");
        }
        if (databases.findByName(name).isPresent()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "A database connection named '" + name + "' already exists");
        }
        Instant now = Instant.now();
        RemoteDatabase database = new RemoteDatabase(UuidV7.generate(), name, url, username,
                cipher.seal(request.password()), false, null, null, null, now, now);
        databases.save(database);
        audit.record("USER", adminId.toString(), "database.create", "DATABASE",
                database.id().toString(), null, name + " " + url, null);
        return toDto(database);
    }

    @Transactional
    public RemoteDatabaseDto update(UUID adminId, UUID id, UpdateRemoteDatabaseRequest request) {
        if (request == null) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED, "Request body required");
        }
        RemoteDatabase existing = require(id);
        String name = request.name() == null ? existing.name()
                : requireText(request.name(), "name", 1, 100);
        String url = request.jdbcUrl() == null ? existing.jdbcUrl()
                : validatedUrl(request.jdbcUrl());
        String username = request.username() == null ? existing.username()
                : requireText(request.username(), "username", 1, 200);
        String sealed = existing.passwordCipher();
        boolean passwordChanged = request.password() != null && !request.password().isBlank();
        if (passwordChanged) {
            sealed = cipher.seal(request.password());
        }
        if (databases.findByName(name).filter(d -> !d.id().equals(id)).isPresent()) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "A database connection named '" + name + "' already exists");
        }
        RemoteDatabase updated = new RemoteDatabase(existing.id(), name, url, username, sealed,
                existing.enabled(), existing.lastTestedAt(), existing.lastTestOk(),
                existing.lastTestError(), existing.createdAt(), Instant.now());
        databases.save(updated);
        // The active override must reflect the new coordinates immediately.
        if (updated.enabled()) {
            try {
                RemoteDataSourceOverride.write(overrideFile(), updated.jdbcUrl(),
                        updated.username(), cipher.unseal(sealed));
            } catch (Exception e) {
                throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                        "Cannot refresh the data source override: " + e.getMessage());
            }
        }
        audit.record("USER", adminId.toString(), "database.update", "DATABASE",
                id.toString(), existing.jdbcUrl(), url + " (passwordChanged="
                        + passwordChanged + ")", null);
        return toDto(updated);
    }

    @Transactional
    public void delete(UUID adminId, UUID id) {
        RemoteDatabase existing = require(id);
        if (existing.enabled()) {
            deactivate(adminId);
        }
        databases.deleteById(id);
        audit.record("USER", adminId.toString(), "database.delete", "DATABASE",
                id.toString(), existing.name(), null, null);
    }

    // ---- connectivity ----

    /** Opens a live JDBC connection with a bounded timeout and records the result. */
    public TestResultDto test(UUID adminId, UUID id) {
        RemoteDatabase database = require(id);
        Probe probe = probe(database.jdbcUrl(), database.username(),
                cipher.unseal(database.passwordCipher()));
        RemoteDatabase updated = new RemoteDatabase(database.id(), database.name(),
                database.jdbcUrl(), database.username(), database.passwordCipher(),
                database.enabled(), probe.testedAt(), probe.ok(), probe.error(),
                database.createdAt(), Instant.now());
        databases.save(updated);
        audit.record("USER", adminId.toString(), "database.test", "DATABASE",
                id.toString(), null, probe.ok + (probe.ok ? " " + probe.productName : ""),
                null);
        return new TestResultDto(probe.ok(), probe.ok ? probe.productName : null,
                probe.ok ? probe.productVersion : null, probe.testedAt().toString(),
                probe.error());
    }

    // ---- activation ----

    /**
     * Activates the connection as the store's data source: refuses unless a live
     * probe succeeds, deactivates any previous entry, then writes the override
     * file {@link RemoteDataSourceEnvironmentPostProcessor} applies on restart.
     */
    @Transactional
    public RemoteDatabaseDto activate(UUID adminId, UUID id) {
        RemoteDatabase database = require(id);
        Probe probe = probe(database.jdbcUrl(), database.username(),
                cipher.unseal(database.passwordCipher()));
        RemoteDatabase tested = new RemoteDatabase(database.id(), database.name(),
                database.jdbcUrl(), database.username(), database.passwordCipher(),
                database.enabled(), probe.testedAt(), probe.ok(), probe.error(),
                database.createdAt(), Instant.now());
        if (!probe.ok) {
            databases.save(tested);
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "Cannot activate — the connection test failed: " + probe.error());
        }
        for (RemoteDatabase other : databases.findAll()) {
            boolean enable = other.id().equals(id);
            if (other.enabled() != enable || other.id().equals(id)) {
                databases.save(new RemoteDatabase(other.id(), other.name(), other.jdbcUrl(),
                        other.username(), other.passwordCipher(), enable,
                        other.id().equals(id) ? probe.testedAt() : other.lastTestedAt(),
                        other.id().equals(id) ? probe.ok() : other.lastTestOk(),
                        other.id().equals(id) ? null : other.lastTestError(),
                        other.createdAt(), Instant.now()));
            }
        }
        try {
            RemoteDataSourceOverride.write(overrideFile(), tested.jdbcUrl(),
                    tested.username(), cipher.unseal(tested.passwordCipher()));
        } catch (Exception e) {
            throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                    "Cannot write the data source override file: " + e.getMessage());
        }
        audit.record("USER", adminId.toString(), "database.activate", "DATABASE",
                id.toString(), null, tested.name() + " " + tested.jdbcUrl(), null);
        return toDto(databases.findById(id).orElseThrow());
    }

    /** Drops the override and returns the store to its configured default database. */
    @Transactional
    public void deactivate(UUID adminId) {
        for (RemoteDatabase database : databases.findAll()) {
            if (database.enabled()) {
                databases.save(new RemoteDatabase(database.id(), database.name(),
                        database.jdbcUrl(), database.username(), database.passwordCipher(),
                        false, database.lastTestedAt(), database.lastTestOk(),
                        database.lastTestError(), database.createdAt(), Instant.now()));
                audit.record("USER", adminId.toString(), "database.deactivate", "DATABASE",
                        database.id().toString(), database.name(), null, null);
            }
        }
        try {
            RemoteDataSourceOverride.clear(overrideFile());
        } catch (Exception e) {
            throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                    "Cannot remove the data source override file: " + e.getMessage());
        }
    }

    // ---- status ----

    /** What the running instance is connected to right now (no credentials). */
    public DataSourceStatusDto status() {
        String product = null;
        String version = null;
        String url = null;
        String username = null;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
            version = connection.getMetaData().getDatabaseProductVersion();
            url = connection.getMetaData().getURL();
            username = connection.getMetaData().getUserName();
        } catch (Exception ignored) {
            // Fall back to override-file state only.
        }
        boolean overrideActive = false;
        String overrideName = null;
        if (RemoteDataSourceOverride.load(overrideFile()) != null) {
            overrideActive = true;
            overrideName = databases.findEnabled().map(RemoteDatabase::name).orElse(null);
        }
        return new DataSourceStatusDto(product, version, mask(url), username, overrideActive,
                overrideName);
    }

    // ---- helpers ----

    private RemoteDatabase require(UUID id) {
        return databases.findById(id).orElseThrow(
                () -> new DomainException(StoreErrorCode.NOT_FOUND,
                        "Database connection not found: " + id));
    }

    private Path overrideFile() {
        return RemoteDataSourceOverride.overridePath(properties.remoteDatasourceFile());
    }

    private record Probe(boolean ok, String productName, String productVersion, String error,
            Instant testedAt) {}

    private Probe probe(String url, String username, String password) {
        Instant testedAt = Instant.now();
        Future<Probe> future = PROBES.submit(() -> {
            try (Connection connection = java.sql.DriverManager
                    .getConnection(url, username, password)) {
                return new Probe(true,
                        connection.getMetaData().getDatabaseProductName(),
                        connection.getMetaData().getDatabaseProductVersion(),
                        null, testedAt);
            } catch (Exception e) {
                return new Probe(false, null, null, rootMessage(e), testedAt);
            }
        });
        try {
            return future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Probe(false, null, null,
                    "Timed out after " + TEST_TIMEOUT_SECONDS + "s", testedAt);
        } catch (Exception e) {
            return new Probe(false, null, null, rootMessage(e), testedAt);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    /**
     * Only the drivers the platform ships (PostgreSQL, H2) are accepted, and
     * H2's script-executing URL parameters (INIT/RUNSCRIPT) are rejected — a
     * JDBC URL is otherwise an arbitrary code-execution vector.
     */
    private static String validatedUrl(String raw) {
        String url = requireText(raw, "jdbcUrl", 1, 500);
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("jdbc:postgresql://") || lower.startsWith("jdbc:h2:"))) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "jdbcUrl must be jdbc:postgresql://host:port/db or jdbc:h2:… "
                            + "(the two drivers this platform ships)");
        }
        if (lower.contains("init=") || lower.contains("runscript")) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    "jdbcUrl must not contain INIT/RUNSCRIPT parameters");
        }
        return url;
    }

    private static String requireText(String raw, String field, int min, int max) {
        if (raw == null || raw.trim().length() < min || raw.trim().length() > max) {
            throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                    field + " must be " + min + "-" + max + " characters");
        }
        return raw.trim();
    }

    private static String mask(String url) {
        return url == null ? null : url.replaceAll("(?i)(password=)[^;&]*", "$1***");
    }

    private static RemoteDatabaseDto toDto(RemoteDatabase database) {
        return new RemoteDatabaseDto(database.id().toString(), database.name(),
                database.jdbcUrl(), database.username(), database.enabled(),
                database.lastTestedAt() == null ? null : database.lastTestedAt().toString(),
                database.lastTestOk(), database.lastTestError(),
                database.createdAt().toString(), database.updatedAt().toString());
    }
}
