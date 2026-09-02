package dev.infinia.store.app.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;

/**
 * The remote data-source override file (远程数据库配置): a java properties file
 * the admin console writes when activating a registered remote database, and
 * {@link RemoteDataSourceEnvironmentPostProcessor} reads on startup to override
 * {@code spring.datasource.*}. Shared by the service layer (writes) and the
 * environment post-processor (reads) so both agree on the location.
 *
 * <p>Precedence after activation is: command line &gt; system properties &gt;
 * environment variables &gt; this override &gt; application.yml — operators can
 * always force a different database without touching the console.</p>
 */
public final class RemoteDataSourceOverride {

    public static final String URL_KEY = "spring.datasource.url";
    public static final String USERNAME_KEY = "spring.datasource.username";
    public static final String PASSWORD_KEY = "spring.datasource.password";

    private RemoteDataSourceOverride() {}

    /** Default anchor: the same per-user home the local H2 profile uses. */
    public static Path overridePath(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home"), ".infinia-store",
                "remote-datasource.properties");
    }

    /**
     * Writes the override atomically with owner-only permissions; never throws.
     * Plain {@code key=value} lines (Properties.store would escape every colon
     * in JDBC URLs, making the file unreadable for operators).
     */
    public static void write(Path file, String url, String username, String password)
            throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# FengYu Store remote data source override (admin console).\n")
                .append("# Delete this file (or deactivate in the console) to fall ")
                .append("back to application.yml.\n")
                .append("# activated-at=").append(Instant.now()).append('\n')
                .append(URL_KEY).append('=').append(escape(url)).append('\n')
                .append(USERNAME_KEY).append('=').append(escape(username)).append('\n')
                .append(PASSWORD_KEY).append('=')
                .append(escape(password == null ? "" : password)).append('\n');
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: rely on the directory's own permissions.
        }
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** java.util.Properties escape semantics for the value part. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Removes the override; missing files are a no-op. */
    public static void clear(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /** Parsed override, or null when absent/unreadable (never breaks startup). */
    public static Properties load(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            return props;
        } catch (IOException e) {
            return null;
        }
    }
}
