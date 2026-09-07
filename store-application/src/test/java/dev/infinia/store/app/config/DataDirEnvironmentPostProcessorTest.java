package dev.infinia.store.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the embedded H2 database lives: development resolves a temp folder under
 * the project root (walking up from Maven's module-dir cwd), production anchors
 * on the running directory, an explicit store.data-dir always wins, and the
 * one-time move of the legacy ~/.infinia-store database never clobbers anything.
 */
class DataDirEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    private final DeferredLogFactory logs =
            supplier -> org.apache.commons.logging.LogFactory.getLog("data-dir-test");

    @Test
    void developmentResolvesTempFolderUnderProjectRoot() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        Path module = Files.createDirectories(root.resolve("store-application"));

        assertEquals(root.resolve("tmp").resolve("database"),
                DataDirEnvironmentPostProcessor.resolveDataDir(module, java.util.Set.of("local")));
        assertEquals(root.resolve("tmp").resolve("database"),
                DataDirEnvironmentPostProcessor.resolveDataDir(root, java.util.Set.of("dev")));
    }

    @Test
    void productionAnchorsOnTheWorkingDirectoryWithoutWalkingUp() {
        Path nested = tempDir.resolve("deploy").resolve("bin");

        assertEquals(nested.resolve("database"),
                DataDirEnvironmentPostProcessor.resolveDataDir(nested, java.util.Set.of()));
        assertEquals(nested.resolve("database"),
                DataDirEnvironmentPostProcessor.resolveDataDir(nested, java.util.Set.of("prod")));
    }

    @Test
    void withoutRepoMarkersTheWorkingDirectoryStandsInForTheProjectRoot() {
        Path detached = tempDir.resolve("somewhere-else");

        assertEquals(detached.resolve("tmp").resolve("database"),
                DataDirEnvironmentPostProcessor.resolveDataDir(detached, java.util.Set.of("local")));
    }

    @Test
    void processorPublishesDataDirBelowSystemEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");

        new DataDirEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        assertEquals(java.nio.file.Path.of(System.getProperty("user.dir"))
                        .resolve("database").toString(),
                environment.getProperty("store.data-dir"));
        // Production keeps the home-anchored blob/key defaults — nothing
        // store-generated moves next to the jar except the database.
        assertNull(environment.getProperty("store.blob-dir"));
        assertNull(environment.getProperty("store.key-dir"));
        // application.yml may reference ${store.data-dir} from anywhere, so the
        // published source must sit above the yml but below operator overrides.
        int envIndex = -1;
        int dataDirIndex = -1;
        int i = 0;
        for (org.springframework.core.env.PropertySource<?> source
                : environment.getPropertySources()) {
            if (org.springframework.core.env.StandardEnvironment
                    .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(source.getName())) {
                envIndex = i;
            }
            if (DataDirEnvironmentPostProcessor.PROPERTY_SOURCE_NAME
                    .equals(source.getName())) {
                dataDirIndex = i;
            }
            i++;
        }
        assertTrue(envIndex >= 0 && envIndex < dataDirIndex);
    }

    @Test
    void explicitConfigurationWinsAndSkipsMigration() throws IOException {
        Path explicit = tempDir.resolve("custom-db");
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new org.springframework.core.env
                .MapPropertySource("configured",
                java.util.Map.of(DataDirEnvironmentPostProcessor.DATA_DIR_PROPERTY,
                        explicit.toString())));

        new DataDirEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        assertEquals(explicit.toString(), environment.getProperty("store.data-dir"));
        assertFalse(Files.exists(explicit));
    }

    @Test
    void developmentAnchorsBlobKeyAndExportDirsUnderTheProjectTmpFolder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");

        new DataDirEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        Path tmp = Path.of(environment.getProperty("store.data-dir")).getParent();
        assertEquals(tmp.resolve("blobs").toString(),
                environment.getProperty("store.blob-dir"));
        assertEquals(tmp.resolve("keys").toString(),
                environment.getProperty("store.key-dir"));
        assertEquals(tmp.resolve("git-exports").toString(),
                environment.getProperty("store.export-dir"));
    }

    @Test
    void operatorConfiguredStorageDirWinsButSiblingsStillDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new org.springframework.core.env
                .MapPropertySource("configured",
                java.util.Map.of("store.key-dir", "/etc/store-keys")));

        new DataDirEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        assertEquals("/etc/store-keys", environment.getProperty("store.key-dir"));
        assertNotNull(environment.getProperty("store.blob-dir"));
    }

    @Test
    void attachedConfigurationPropertiesWrapperDoesNotCountAsOperatorConfig() {
        // Spring Boot attaches a "configurationProperties" wrapper above the
        // whole source list before post-processors run; it mirrors application
        // yml too, so a yml default must not block the development defaults.
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");
        environment.getPropertySources().addFirst(new org.springframework.core.env
                .MapPropertySource(
                DataDirEnvironmentPostProcessor.ATTACHED_PROPERTY_SOURCE_NAME,
                java.util.Map.of("store.blob-dir", "${user.home}/.infinia-store/blobs")));

        new DataDirEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        // The real wrapper delegates getProperty to the sources below it, so a
        // plain map cannot imitate it faithfully — assert on what was published
        // instead: the development default must be in the EPP's own source.
        Path tmp = Path.of(environment.getProperty("store.data-dir")).getParent();
        org.springframework.core.env.PropertySource<?> published = environment
                .getPropertySources().get(DataDirEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertEquals(tmp.resolve("blobs").toString(), published.getProperty("store.blob-dir"));
    }

    @Test
    void legacyDatabaseIsMovedWhenTargetIsEmpty() throws IOException {
        Path legacy = Files.createDirectory(tempDir.resolve("legacy"));
        Path target = tempDir.resolve("data");
        Files.writeString(legacy.resolve("storedb.mv.db"), "db");
        Files.writeString(legacy.resolve("storedb.trace.db"), "trace");

        assertTrue(DataDirEnvironmentPostProcessor.moveLegacyDatabase(legacy, target));

        assertEquals("db", Files.readString(target.resolve("storedb.mv.db")));
        assertEquals("trace", Files.readString(target.resolve("storedb.trace.db")));
        assertFalse(Files.exists(legacy.resolve("storedb.mv.db")));
    }

    @Test
    void legacyMoveIsSkippedWhenLockedTargetExistsOrNothingToMove() throws IOException {
        // A running instance holds H2's lock file — never move underneath it.
        Path locked = Files.createDirectory(tempDir.resolve("locked"));
        Path targetA = tempDir.resolve("target-a");
        Files.writeString(locked.resolve("storedb.mv.db"), "db");
        Files.writeString(locked.resolve("storedb.lock.db"), "lock");
        assertFalse(DataDirEnvironmentPostProcessor.moveLegacyDatabase(locked, targetA));
        assertTrue(Files.exists(locked.resolve("storedb.mv.db")));

        // An existing database at the target is never overwritten.
        Path populated = Files.createDirectory(tempDir.resolve("populated"));
        Path targetB = Files.createDirectory(tempDir.resolve("target-b"));
        Files.writeString(populated.resolve("storedb.mv.db"), "old");
        Files.writeString(targetB.resolve("storedb.mv.db"), "current");
        assertFalse(DataDirEnvironmentPostProcessor.moveLegacyDatabase(populated, targetB));
        assertEquals("current", Files.readString(targetB.resolve("storedb.mv.db")));

        // No legacy database: nothing happens, not even the target directory.
        Path empty = Files.createDirectory(tempDir.resolve("empty"));
        Path targetC = tempDir.resolve("target-c");
        assertFalse(DataDirEnvironmentPostProcessor.moveLegacyDatabase(empty, targetC));
        assertFalse(Files.exists(targetC));
    }
}
