package dev.infinia.store.app;

import dev.infinia.store.app.config.RemoteDataSourceEnvironmentPostProcessor;
import dev.infinia.store.app.config.RemoteDataSourceOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The startup half of remote-database configuration (远程数据库配置): the override
 * file written by the admin console must land between environment variables and
 * application config, so operators can still force a different database.
 */
class RemoteDataSourceOverrideTest {

    @TempDir
    Path tempDir;

    private final DeferredLogFactory logs =
            supplier -> org.apache.commons.logging.LogFactory.getLog("remote-db-test");

    @Test
    void overrideBeatsYamlButNotEnvironmentVariables() throws Exception {
        Path file = tempDir.resolve("remote-datasource.properties");
        RemoteDataSourceOverride.write(file, "jdbc:postgresql://db.example.com:5432/store",
                "store", "topsecret");

        StandardEnvironment environment = new StandardEnvironment();
        // The console stores its override at a configured location…
        environment.getPropertySources().addFirst(new MapPropertySource("configured",
                Map.of("store.remote-datasource-file", file.toString())));
        // …and application.yml already defines a default database.
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig",
                Map.of("spring.datasource.url", "jdbc:h2:file:./default")));

        new RemoteDataSourceEnvironmentPostProcessor(logs).postProcessEnvironment(environment, null);

        // The override wins over application.yml…
        assertEquals("jdbc:postgresql://db.example.com:5432/store",
                environment.getProperty("spring.datasource.url"));
        assertEquals("topsecret", environment.getProperty("spring.datasource.password"));
        // …and sits directly below the standard system-environment source, so
        // SPRING_DATASOURCE_* variables (and command-line/JVM properties) still
        // outrank it — operators can always force a different database.
        Iterable<org.springframework.core.env.PropertySource<?>> sources =
                environment.getPropertySources();
        int envIndex = -1;
        int overrideIndex = -1;
        int appIndex = -1;
        int i = 0;
        for (org.springframework.core.env.PropertySource<?> source : sources) {
            if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                    .equals(source.getName())) {
                envIndex = i;
            }
            if (RemoteDataSourceEnvironmentPostProcessor.PROPERTY_SOURCE_NAME
                    .equals(source.getName())) {
                overrideIndex = i;
            }
            if ("applicationConfig".equals(source.getName())) {
                appIndex = i;
            }
            i++;
        }
        assertTrue(envIndex < overrideIndex, "systemEnvironment ranks above the override");
        assertTrue(overrideIndex < appIndex, "override ranks above application config");
    }

    @Test
    void missingOrMalformedOverrideNeverBreaksStartup() {
        StandardEnvironment environment = new StandardEnvironment();
        // No file: no-op.
        new RemoteDataSourceEnvironmentPostProcessor(logs)
                .postProcessEnvironment(environment, null);
        assertNull(environment.getProperty("spring.datasource.url"));

        // Malformed file (unparseable bytes): still no-op, no exception.
        try {
            java.nio.file.Files.write(tempDir.resolve("broken.properties"),
                    new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        } catch (Exception ignored) {
            // filesystem-dependent
        }
        StandardEnvironment withBroken = new StandardEnvironment();
        withBroken.getPropertySources().addFirst(new MapPropertySource("configured",
                Map.of("store.remote-datasource-file",
                        tempDir.resolve("broken.properties").toString())));
        assertDoesNotThrow(() -> new RemoteDataSourceEnvironmentPostProcessor(logs)
                .postProcessEnvironment(withBroken, null));
    }

    @Test
    void clearRemovesTheOverride() throws Exception {
        Path file = tempDir.resolve("remote-datasource.properties");
        RemoteDataSourceOverride.write(file, "jdbc:h2:mem:x", "sa", "");
        RemoteDataSourceOverride.clear(file);
        assertFalse(java.nio.file.Files.exists(file));
        RemoteDataSourceOverride.clear(file); // idempotent
    }
}
