package dev.infinia.store.app.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Applies the admin-configured remote data source (远程数据库配置) at startup:
 * when the override file exists (a remote database was activated in the admin
 * console), its {@code spring.datasource.*} entries are inserted below the
 * system-environment property source — above application.yml, below command
 * line, JVM system properties and OS environment variables, so operators can
 * always override the override.
 */
public class RemoteDataSourceEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /** After ConfigDataEnvironmentPostProcessor (10) so yml values resolve. */
    public static final int ORDER = 20;

    public static final String PROPERTY_SOURCE_NAME = "remoteDatasourceOverride";

    private final Log log;

    public RemoteDataSourceEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(RemoteDataSourceEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        try {
            Properties override = RemoteDataSourceOverride.load(RemoteDataSourceOverride
                    .overridePath(environment.getProperty("store.remote-datasource-file")));
            if (override == null
                    || override.getProperty(RemoteDataSourceOverride.URL_KEY) == null) {
                return;
            }
            Map<String, Object> source = new HashMap<>();
            override.forEach((key, value) -> source.put(String.valueOf(key), value));
            environment.getPropertySources().addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(PROPERTY_SOURCE_NAME, source));
            log.info("Remote data source override active: "
                    + mask(override.getProperty(RemoteDataSourceOverride.URL_KEY)));
        } catch (RuntimeException e) {
            log.warn("Ignoring unreadable remote data source override: " + e.getMessage());
        }
    }

    private static String mask(Object url) {
        return url == null ? null : url.toString()
                .replaceAll("(?i)(password=)[^;&]*", "$1***");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
