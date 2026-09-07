package dev.infinia.store.app.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves where the store's local state lives and publishes it for
 * application.yml:
 *
 * <ul>
 *   <li>Development ({@code local}/{@code dev} profiles): a temporary folder under
 *       the project root — {@code <root>/tmp/database} — located by walking up from
 *       the working directory to the nearest {@code .git}/{@code mvnw} marker, so
 *       IDEA runs (project-root cwd) and Maven runs (module-dir cwd) land on the
 *       same files. Blobs, keys and git exports anchor under the same
 *       {@code <root>/tmp/} folder, so nothing store-generated lands in the user
 *       home.</li>
 *   <li>Production: the {@code database} folder inside the program's running
 *       directory; blob/key locations keep their home-anchored defaults.</li>
 * </ul>
 *
 * <p>An explicit {@code store.data-dir} (system property, {@code STORE_DATA_DIR}
 * environment variable, …) always wins and disables the legacy migration below.
 * On the first development boot after this change, the previous
 * {@code ~/.infinia-store/storedb} files are moved to the new location — never
 * while another instance holds the H2 lock, never over an existing database.</p>
 */
public class DataDirEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /** After ConfigDataEnvironmentPostProcessor (10) so profiles resolve. */
    public static final int ORDER = 20;

    public static final String PROPERTY_SOURCE_NAME = "storeDataDir";
    public static final String DATA_DIR_PROPERTY = "store.data-dir";

    private static final Set<String> DEV_PROFILES = Set.of("local", "dev");

    /** Store-owned state that development keeps under the project tmp/ folder. */
    private static final Map<String, String> DEV_STORAGE_DIRS = Map.of(
            "store.blob-dir", "blobs",
            "store.key-dir", "keys",
            "store.export-dir", "git-exports");

    /**
     * Spring Boot's attached {@code configurationProperties} wrapper
     * ({@code ConfigurationPropertySources#ATTACHED_PROPERTY_SOURCE_NAME},
     * private in Boot 4): mirrors every source below it, yml included.
     */
    static final String ATTACHED_PROPERTY_SOURCE_NAME = "configurationProperties";

    private final Log log;

    public DataDirEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DataDirEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        Set<String> activeProfiles = Set.of(environment.getActiveProfiles());
        String configured = environment.getProperty(DATA_DIR_PROPERTY);
        Path dataDir = configured == null || configured.isBlank()
                ? resolveDataDir(workingDir(), activeProfiles)
                : Path.of(configured.trim());
        Map<String, Object> source = new HashMap<>();
        source.put(DATA_DIR_PROPERTY, dataDir.toString());
        if (activeProfiles.stream().anyMatch(DEV_PROFILES::contains)) {
            // Published only when nothing operator-controlled sets them; values
            // in application.yml (e.g. the home-anchored defaults) do not count,
            // because this source ranks above the yml by design.
            for (Map.Entry<String, String> dir : DEV_STORAGE_DIRS.entrySet()) {
                if (!operatorSet(environment, dir.getKey())) {
                    source.put(dir.getKey(), dataDir.getParent()
                            .resolve(dir.getValue()).toString());
                }
            }
        }
        environment.getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(PROPERTY_SOURCE_NAME, source));
        if (configured == null || configured.isBlank()) {
            migrateLegacyDatabase(environment, dataDir);
        }
    }

    /**
     * True when a command-line argument, JVM system property or OS environment
     * variable names the property — everything down to the system-environment
     * source. application.yml sits below that line and must not block the
     * development defaults. The {@code configurationProperties} wrapper Spring
     * Boot attaches above everything is skipped: it mirrors the whole source
     * list (yml included), so a yml default would otherwise masquerade as
     * operator intent.
     */
    private static boolean operatorSet(ConfigurableEnvironment environment,
            String key) {
        for (org.springframework.core.env.PropertySource<?> source
                : environment.getPropertySources()) {
            if (ATTACHED_PROPERTY_SOURCE_NAME.equals(source.getName())) {
                continue;
            }
            if (source.containsProperty(key)) {
                return true;
            }
            if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    .equals(source.getName())) {
                return false;
            }
        }
        return false;
    }

    /** Development puts the database under the project root, production in cwd. */
    static Path resolveDataDir(Path workingDir, Set<String> activeProfiles) {
        boolean development = activeProfiles.stream().anyMatch(DEV_PROFILES::contains);
        Path root = development ? projectRoot(workingDir) : workingDir;
        return development ? root.resolve("tmp").resolve("database")
                : root.resolve("database");
    }

    /** Nearest ancestor (or start) containing a repo-root marker (.git / mvnw). */
    static Path projectRoot(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git")) || Files.exists(dir.resolve("mvnw"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return start.toAbsolutePath().normalize();
    }

    private void migrateLegacyDatabase(ConfigurableEnvironment environment, Path dataDir) {
        Set<String> active = Set.of(environment.getActiveProfiles());
        if (active.stream().noneMatch(DEV_PROFILES::contains)) {
            // Production never used the legacy user-home location.
            return;
        }
        try {
            if (moveLegacyDatabase(Path.of(System.getProperty("user.home"),
                    ".infinia-store"), dataDir)) {
                log.info("Moved legacy local database to " + dataDir);
            }
        } catch (IOException e) {
            log.warn("Could not move the legacy ~/.infinia-store database to "
                    + dataDir + " (" + e.getMessage() + "); continuing on a fresh "
                    + "database. Move storedb.mv.db by hand if the old data matters.");
        }
    }

    /**
     * One-time move of {@code <legacy>/storedb.*} into {@code dataDir}. A no-op
     * (returns false) when there is nothing to move, the target already has a
     * database, or the legacy database is locked by a running instance
     * (H2's {@code storedb.lock.db}).
     */
    static boolean moveLegacyDatabase(Path legacyDir, Path dataDir) throws IOException {
        Path legacyDb = legacyDir.resolve("storedb.mv.db");
        if (!Files.exists(legacyDb) || Files.exists(dataDir.resolve("storedb.mv.db"))
                || Files.exists(legacyDir.resolve("storedb.lock.db"))) {
            return false;
        }
        Files.createDirectories(dataDir);
        Files.move(legacyDb, dataDir.resolve("storedb.mv.db"));
        Path legacyTrace = legacyDir.resolve("storedb.trace.db");
        if (Files.exists(legacyTrace)) {
            Files.move(legacyTrace, dataDir.resolve("storedb.trace.db"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private static Path workingDir() {
        return Path.of(System.getProperty("user.dir"));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
